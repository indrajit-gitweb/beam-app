package com.beam.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocketFrame
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Beam LAN Server — runs inside the Android app.
 * Mirrors the functionality of beam-electron/lan-server.js.
 *
 * HTTP endpoints:
 *   GET  /            → serve index.html from assets
 *   GET  /health      → {"ok":true,"name":"...","port":7777}
 *   GET  /peers       → list of connected peers
 *   POST /upload      → receive file, store in memory, notify target
 *   GET  /download/:id → serve file (deleted after delivery)
 *
 * WebSocket (same port, upgrades via NanoWSD):
 *   register, signal, transfer-request, transfer-accept, transfer-decline
 */
class BeamLanServer(
    private val context: Context,
    private val deviceName: String,
    port: Int = PORT
) : NanoWSD(port) {

    // ── Peer registry ───────────────────────────────────────────────────────────
    data class PeerMeta(val name: String, val deviceType: String, val signal: String = "●●●● Excellent")
    private val peers    = ConcurrentHashMap<String, BeamWebSocket>()
    private val peerMeta = ConcurrentHashMap<String, PeerMeta>()

    // ── In-memory file store ────────────────────────────────────────────────────
    data class StoredFile(
        val buffer: ByteArray, val name: String, val size: Int,
        val type: String, val fromPeer: String, val toPeer: String,
        val fileIndex: Int, val totalFiles: Int
    )
    private val fileStore = ConcurrentHashMap<String, StoredFile>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── HTTP ────────────────────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): NanoHTTPD.Response {
        // ── WebSocket upgrade must be handled by NanoWSD (super) ──────────────
        // If we don't delegate, our serve() override bypasses NanoWSD's
        // WebSocket detection entirely, causing the browser to get HTTP 200
        // instead of HTTP 101 Switching Protocols.
        val upgradeHeader = session.headers["upgrade"]
        if (upgradeHeader != null && upgradeHeader.lowercase().contains("websocket")) {
            return super.serve(session)
        }

        val uri    = session.uri ?: "/"
        val method = session.method

        fun cors(r: NanoHTTPD.Response): NanoHTTPD.Response {
            r.addHeader("Access-Control-Allow-Origin",  "*")
            r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            r.addHeader("Access-Control-Allow-Headers",
                "Content-Type,X-Filename,X-Filesize,X-Filetype,X-Target-Peer," +
                "X-From-Peer,X-From-Name,X-File-Index,X-Total-Files,X-Expiry-Minutes")
            // Required for Chrome's Private Network Access policy:
            // allows file:// or public origins to fetch local network IPs
            r.addHeader("Access-Control-Allow-Private-Network", "true")
            return r
        }

        if (method == NanoHTTPD.Method.OPTIONS)
            return cors(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NO_CONTENT, "text/plain", ""))

        if (uri == "/health" && method == NanoHTTPD.Method.GET)
            return cors(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json",
                """{"ok":true,"name":"$deviceName","port":${getListeningPort()}}"""))

        if (uri == "/peers" && method == NanoHTTPD.Method.GET) {
            val arr = JSONArray()
            peers.forEach { (id, _) ->
                val m = peerMeta[id] ?: return@forEach
                arr.put(JSONObject().apply { put("id", id); put("name", m.name); put("deviceType", m.deviceType) })
            }
            return cors(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json",
                JSONObject().apply { put("peers", arr) }.toString()))
        }

        if (uri == "/upload" && method == NanoHTTPD.Method.POST)
            return cors(handleUpload(session))

        if (uri.startsWith("/download/") && method == NanoHTTPD.Method.GET)
            return cors(handleDownload(uri.removePrefix("/download/").split("?")[0]))

        // Serve index.html for browser clients
        if (uri == "/" || uri == "/index.html" || uri.isEmpty())
            return cors(serveAsset("index.html", "text/html; charset=utf-8"))

        return cors(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not found"))
    }

    private fun handleUpload(session: IHTTPSession): NanoHTTPD.Response {
        val h          = session.headers
        val filename   = decode(h["x-filename"]   ?: "file")
        val filetype   = h["x-filetype"]          ?: "application/octet-stream"
        val targetPeer = h["x-target-peer"]       ?: ""
        val fromPeer   = h["x-from-peer"]         ?: ""
        val fromName   = decode(h["x-from-name"]  ?: deviceName)
        val fileIndex  = h["x-file-index"]?.toIntOrNull()  ?: 0
        val totalFiles = h["x-total-files"]?.toIntOrNull() ?: 1
        val expiryMins = h["x-expiry-minutes"]?.toIntOrNull()?.coerceIn(10, 60) ?: 10

        return try {
            // Stream in 4MB chunks to avoid OOM on large files
            val out   = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4 * 1024 * 1024)
            var n: Int
            while (session.inputStream.read(chunk).also { n = it } != -1) {
                out.write(chunk, 0, n)
            }
            val buf = out.toByteArray()

            val fileId = generateId()
            fileStore[fileId] = StoredFile(buf, filename, buf.size, filetype, fromPeer, targetPeer, fileIndex, totalFiles)

            // Auto-delete after expiry
            mainHandler.postDelayed({ fileStore.remove(fileId) }, expiryMins * 60 * 1000L)

            Log.d(TAG, "File stored: $filename (${buf.size} B) → $targetPeer")

            // Notify target peer
            peers[targetPeer]?.send(JSONObject().apply {
                put("type",        "file-ready")
                put("fileId",      fileId)
                put("filename",    filename)
                put("filesize",    buf.size)
                put("filetype",    filetype)
                put("fromName",    fromName)
                put("fileIndex",   fileIndex)
                put("totalFiles",  totalFiles)
                put("downloadUrl", "/download/$fileId")
            }.toString())

            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json",
                """{"ok":true,"fileId":"$fileId"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Upload error", e)
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
        }
    }

    private fun handleDownload(fileId: String): NanoHTTPD.Response {
        val file = fileStore[fileId]
            ?: return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not found")

        Log.d(TAG, "Serving: ${file.name} (${file.buffer.size} B)")

        // Delete after 5s to allow stream to complete
        mainHandler.postDelayed({ fileStore.remove(fileId) }, 5000)

        val res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, file.type,
            file.buffer.inputStream(), file.buffer.size.toLong())
        res.addHeader("Content-Disposition", "attachment; filename=\"${encode(file.name)}\"")
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    private fun serveAsset(name: String, mime: String): NanoHTTPD.Response =
        try {
            NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mime, context.assets.open(name))
        } catch (e: IOException) {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "$name not found")
        }

    // ── WebSocket ───────────────────────────────────────────────────────────────

    override fun openWebSocket(handshake: IHTTPSession): NanoWSD.WebSocket = BeamWebSocket(handshake)

    inner class BeamWebSocket(handshake: IHTTPSession) : NanoWSD.WebSocket(handshake) {
        private var peerId: String? = null

        override fun onOpen() {}

        override fun onMessage(message: WebSocketFrame) {
            val json = try { JSONObject(message.textPayload ?: return) } catch (e: Exception) { return }

            when (json.optString("type")) {
                "register" -> {
                    peerId = json.optString("id").takeIf { it.isNotEmpty() } ?: return
                    peerMeta[peerId!!] = PeerMeta(
                        json.optString("name", deviceName),
                        json.optString("deviceType", "phone"),
                        json.optString("signal", "●●●● Excellent")
                    )
                    peers[peerId!!] = this
                    send(JSONObject().apply { put("type", "registered"); put("id", peerId) }.toString())
                    broadcastPeerList()
                    Log.d(TAG, "Peer registered: $peerId (${peerMeta[peerId!!]?.name})")
                    startKeepAlive() // keep connection alive
                }
                "ping" -> { /* keepalive from client — no response needed */ }
                "signal", "transfer-request", "transfer-accept", "transfer-decline" -> {
                    val msgType = json.optString("type")
                    val to      = json.optString("to")
                    val target  = peers[to]
                    Log.d(TAG, "Routing $msgType from $peerId to $to — target=${if(target!=null) "found" else "NOT FOUND"} peers=${peers.keys}")
                    if (target == null) return
                    json.put("from",     peerId ?: "")
                    json.put("fromName", peerMeta[peerId]?.name ?: "")
                    target.send(json.toString())
                }
            }
        }

        override fun onClose(code: CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            Log.d(TAG, "Peer disconnected: $peerId code=$code reason=$reason remote=$initiatedByRemote")
            peerId?.let { id -> peers.remove(id); peerMeta.remove(id); broadcastPeerList() }
        }

        override fun onException(e: IOException) {
            Log.e(TAG, "WS exception for $peerId: ${e.message}")
            peerId?.let { id -> peers.remove(id); peerMeta.remove(id) }
        }

        override fun onPong(pong: WebSocketFrame) {}

        // Send periodic ping to keep connection alive and detect drops
        fun startKeepAlive() {
            mainHandler.postDelayed(object : Runnable {
                override fun run() {
                    try {
                        if (peerId != null) {
                            ping("beam".toByteArray())
                            mainHandler.postDelayed(this, 15000)
                        }
                    } catch (e: Exception) {
                        // Connection dead — cleanup handled by onClose
                    }
                }
            }, 15000)
        }
    }

    private fun broadcastPeerList() {
        val arr = JSONArray()
        peers.forEach { (id, _) ->
            val m = peerMeta[id] ?: return@forEach
            arr.put(JSONObject().apply { put("id", id); put("name", m.name); put("deviceType", m.deviceType); put("signal", m.signal) })
        }
        val msg = JSONObject().apply { put("type", "peers"); put("list", arr) }.toString()
        peers.values.toList().forEach { try { it.send(msg) } catch (e: Exception) {} }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun generateId(): String {
        val b = ByteArray(18); SecureRandom().nextBytes(b)
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    private fun decode(v: String) = try { java.net.URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }
    private fun encode(v: String) = try { java.net.URLEncoder.encode(v, "UTF-8") } catch (e: Exception) { v }

    fun getLocalIp(): String {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
            iface.inetAddresses?.toList()?.forEach { addr ->
                if (!addr.isLoopbackAddress && addr is Inet4Address)
                    return addr.hostAddress ?: ""
            }
        }
        return "127.0.0.1"
    }

    fun getServerUrl(): String = "http://${getLocalIp()}:${getListeningPort()}"

    companion object {
        const val TAG  = "BeamLanServer"
        const val PORT = 7777
    }
}
