package com.beam.app

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Beam LAN Server — LocalSend-style, pure HTTP, no WebSocket.
 *
 * All transfer signaling uses short HTTP requests — no persistent connections
 * that the power manager can kill. This means transfers survive screen-off.
 *
 * Endpoints (port 7777):
 *   GET  /hello              → device info JSON
 *   GET  /health             → device info (compatibility alias)
 *   POST /send-request       → sender announces files; fires broadcast to show popup
 *   GET  /status/{id}        → sender polls: pending | accepted | declined
 *   POST /accept/{id}        → receiver (or UI) accepts the session
 *   POST /decline/{id}       → receiver declines
 *   GET  /pending            → list pending sessions (browser polling)
 *   POST /upload             → receive file bytes, stream directly to Downloads
 *   GET  /                   → serve index.html from assets
 */
class BeamLanServer(
    private val context: Context,
    private val deviceName: String,
    port: Int = PORT
) : NanoHTTPD(port) {

    // ── Session model ──────────────────────────────────────────────────────────

    data class FileInfo(val name: String, val size: Long, val type: String)

    data class TransferSession(
        val sessionId:  String,
        val senderName: String,
        val senderIp:   String,
        val files:      List<FileInfo>,
        @Volatile var status: String = "pending",   // pending | accepted | declined
        val createdAt:  Long = System.currentTimeMillis()
    )

    private val sessions    = ConcurrentHashMap<String, TransferSession>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── HTTP routing ───────────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        val uri    = session.uri ?: "/"
        val method = session.method

        fun cors(r: Response): Response {
            r.addHeader("Access-Control-Allow-Origin",  "*")
            r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            r.addHeader("Access-Control-Allow-Headers",
                "Content-Type,X-Filename,X-Filesize,X-Filetype,X-From-Name," +
                "X-From-Ip,X-Session-Id,X-File-Index,X-Total-Files")
            r.addHeader("Access-Control-Allow-Private-Network", "true")
            return r
        }

        if (method == Method.OPTIONS)
            return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))

        // Device info
        if ((uri == "/hello" || uri == "/health") && method == Method.GET)
            return cors(deviceInfo())

        // Sender announces file(s) → show Accept popup on this device
        if (uri == "/send-request" && method == Method.POST)
            return cors(handleSendRequest(session))

        // Sender polls acceptance status
        if (uri.startsWith("/status/") && method == Method.GET)
            return cors(handleGetStatus(uri.removePrefix("/status/").split("?")[0]))

        // Accept or decline (called by WebView JS or directly via HTTP from UI)
        if (uri.startsWith("/accept/") && (method == Method.POST || method == Method.GET))
            return cors(handleAccept(uri.removePrefix("/accept/").split("?")[0]))
        if (uri.startsWith("/decline/") && (method == Method.POST || method == Method.GET))
            return cors(handleDecline(uri.removePrefix("/decline/").split("?")[0]))

        // Browser polls for pending incoming requests
        if (uri == "/pending" && method == Method.GET)
            return cors(handlePending())

        // File receive — stream directly to Downloads (no memory buffer!)
        if (uri == "/upload" && method == Method.POST)
            return cors(handleUpload(session))

        // Serve app
        if (uri == "/" || uri == "/index.html" || uri.isEmpty())
            return cors(serveAsset("index.html", "text/html; charset=utf-8"))

        return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found"))
    }

    // ── /hello ─────────────────────────────────────────────────────────────────

    private fun deviceInfo(): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"ok":true,"name":"$deviceName","platform":"android","version":2,"port":$PORT}""")

    // ── /send-request ──────────────────────────────────────────────────────────

    private fun handleSendRequest(session: IHTTPSession): Response {
        return try {
            val body       = readBodyString(session)
            val json       = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val sessionId  = generateId()
            val senderName = json.optString("senderName", "Unknown")
            val senderIp   = session.remoteIpAddress ?: json.optString("senderIp", "")
            val filesArr   = json.optJSONArray("files") ?: JSONArray()
            val files = (0 until filesArr.length()).map { i ->
                val f = filesArr.getJSONObject(i)
                FileInfo(
                    f.optString("name", "file"),
                    f.optLong("size", 0),
                    f.optString("type", "application/octet-stream")
                )
            }

            sessions[sessionId] = TransferSession(sessionId, senderName, senderIp, files)
            // Auto-expire after 5 minutes
            mainHandler.postDelayed({ sessions.remove(sessionId) }, 5 * 60_000L)

            // Wake up the UI regardless of whether the screen is on
            context.sendBroadcast(Intent(ACTION_INCOMING_REQUEST).apply {
                putExtra("sessionId",  sessionId)
                putExtra("senderName", senderName)
                putExtra("senderIp",   senderIp)
                putExtra("fileCount",  files.size)
                putExtra("fileName",   files.firstOrNull()?.name  ?: "")
                putExtra("fileSize",   files.firstOrNull()?.size  ?: 0L)
                putExtra("totalSize",  files.sumOf { it.size })
            })

            Log.d(TAG, "Incoming request $sessionId from $senderName (${files.size} file(s))")
            newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"ok":true,"sessionId":"$sessionId","status":"pending"}""")
        } catch (e: Exception) {
            Log.e(TAG, "send-request error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
        }
    }

    // ── /status/:id ────────────────────────────────────────────────────────────

    private fun handleGetStatus(sessionId: String): Response {
        val sess = sessions[sessionId]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                """{"status":"not-found"}""")
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"status":"${sess.status}"}""")
    }

    // ── /accept/:id  /decline/:id ──────────────────────────────────────────────

    private fun handleAccept(sessionId: String): Response {
        acceptSession(sessionId)
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true}""")
    }

    private fun handleDecline(sessionId: String): Response {
        declineSession(sessionId)
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true}""")
    }

    // ── /pending ───────────────────────────────────────────────────────────────

    private fun handlePending(): Response {
        val arr = JSONArray()
        sessions.values
            .filter { it.status == "pending" }
            .sortedBy { it.createdAt }
            .forEach { sess ->
                arr.put(JSONObject().apply {
                    put("sessionId",  sess.sessionId)
                    put("senderName", sess.senderName)
                    put("senderIp",   sess.senderIp)
                    put("fileCount",  sess.files.size)
                    put("fileName",   sess.files.firstOrNull()?.name ?: "")
                    put("fileSize",   sess.files.firstOrNull()?.size ?: 0L)
                    put("totalSize",  sess.files.sumOf { it.size })
                })
            }
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            JSONObject().apply { put("sessions", arr) }.toString())
    }

    // ── /upload ────────────────────────────────────────────────────────────────
    // Streams file bytes directly to the Downloads folder — never buffers the
    // whole file in RAM. The screen can be off on the receiver the entire time.

    private fun handleUpload(session: IHTTPSession): Response {
        val h          = session.headers
        val filename   = decode(h["x-filename"]  ?: "beam-file")
        val fromName   = decode(h["x-from-name"] ?: "sender")
        val contentLen = h["content-length"]?.toLongOrNull() ?: -1L
        val fileIndex  = h["x-file-index"]?.toIntOrNull()  ?: 0
        val totalFiles = h["x-total-files"]?.toIntOrNull() ?: 1

        Log.d(TAG, "Uploading: $filename from $fromName ($contentLen bytes)")

        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val safe = filename.replace(Regex("[/\\\\?%*:|\"<>]"), "_")
            var dest = File(downloadsDir, safe)
            var i = 1
            while (dest.exists()) {
                val ext  = if (safe.contains('.')) ".${safe.substringAfterLast('.')}" else ""
                val base = if (safe.contains('.')) safe.substringBeforeLast('.') else safe
                dest = File(downloadsDir, "${base}_($i)$ext")
                i++
            }

            val fos = FileOutputStream(dest)
            val buf = ByteArray(64 * 1024)   // 64 KB read buffer
            var n: Int
            var total = 0L

            try {
                if (contentLen > 0) {
                    // Exact read — avoids blocking at end of stream
                    var remaining = contentLen
                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        n = session.inputStream.read(buf, 0, toRead)
                        if (n == -1) break
                        fos.write(buf, 0, n)
                        total += n
                        remaining -= n
                        // Broadcast progress so UI can show live %
                        val pct = (total * 100 / contentLen).toInt()
                        context.sendBroadcast(Intent(BeamTransferService.ACTION_TRANSFER_PROGRESS).apply {
                            putExtra("pct", pct)
                            putExtra("filename", filename)
                        })
                    }
                } else {
                    while (session.inputStream.read(buf).also { n = it } != -1) {
                        fos.write(buf, 0, n); total += n
                    }
                }
            } finally { fos.flush(); fos.close() }

            Log.d(TAG, "Saved $total bytes → ${dest.absolutePath}")

            // Notify user
            BeamNotificationHelper.showComplete(
                context, "Received $filename",
                if (totalFiles > 1) "File ${fileIndex + 1}/$totalFiles from $fromName"
                else "From $fromName — saved to Downloads"
            )

            // Update UI
            context.sendBroadcast(Intent(BeamTransferService.ACTION_TRANSFER_COMPLETE).apply {
                putExtra("filename",  filename)
                putExtra("savedPath", dest.absolutePath)
                putExtra("fromName",  fromName)
            })

            newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"ok":true,"saved":"${encode(dest.name)}","size":$total}""")
        } catch (e: Exception) {
            Log.e(TAG, "Upload error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
        }
    }

    // ── Public API for WebInterface ────────────────────────────────────────────

    fun acceptSession(sessionId: String) {
        sessions[sessionId]?.status = "accepted"
        Log.d(TAG, "Session accepted: $sessionId")
    }

    fun declineSession(sessionId: String) {
        sessions[sessionId]?.status = "declined"
        Log.d(TAG, "Session declined: $sessionId")
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun readBodyString(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toIntOrNull()?.coerceAtMost(65_536) ?: return "{}"
        if (len <= 0) return "{}"
        val bytes = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = session.inputStream.read(bytes, offset, len - offset)
            if (n == -1) break
            offset += n
        }
        return String(bytes, 0, offset)
    }

    private fun serveAsset(name: String, mime: String): Response =
        try { newChunkedResponse(Response.Status.OK, mime, context.assets.open(name)) }
        catch (e: Exception) { newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "$name not found") }

    private fun generateId(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b)
        return android.util.Base64.encodeToString(
            b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }

    private fun decode(v: String) = try { java.net.URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { v }
    private fun encode(v: String) = try { java.net.URLEncoder.encode(v, "UTF-8") } catch (_: Exception) { v }

    fun getLocalIp(): String {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
            iface.inetAddresses?.toList()?.forEach { addr ->
                if (!addr.isLoopbackAddress && addr is Inet4Address)
                    return addr.hostAddress ?: ""
            }
        }
        return "127.0.0.1"
    }

    fun getServerUrl(): String = "http://${getLocalIp()}:$PORT"

    companion object {
        const val TAG                     = "BeamLanServer"
        const val PORT                    = 7777
        const val ACTION_INCOMING_REQUEST = "com.beam.app.INCOMING_REQUEST"
    }
}
