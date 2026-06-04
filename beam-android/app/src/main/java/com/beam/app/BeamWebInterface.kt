package com.beam.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface

class BeamWebInterface(private val context: Context) {

    @Volatile private var lanServerUrl: String = ""
    private var beamLanServer: BeamLanServer? = null

    // File URIs stored when the system file picker returns — used by
    // startLocalSendUpload() so the Foreground Service can stream them
    // from disk without loading into JavaScript memory.
    @Volatile private var storedUris: List<Uri> = emptyList()

    fun setLanServerUrl(url: String) { lanServerUrl = url }
    fun setLanServer(server: BeamLanServer?) { beamLanServer = server }

    /** Called by MainActivity after file picker returns */
    fun storeSelectedUris(uris: Array<Uri>) {
        storedUris = uris.toList()
    }

    // ── Basic info ─────────────────────────────────────────────────────────────

    @JavascriptInterface fun isNativeApp(): Boolean   = true
    @JavascriptInterface fun getPlatform(): String    = "android"
    @JavascriptInterface fun getDeviceName(): String  = Build.MODEL
    @JavascriptInterface fun getDeviceType(): String  = "phone"

    @JavascriptInterface fun getLanServerUrl(): String {
        if (beamLanServer?.isAlive == true) return "http://localhost:${BeamLanServer.PORT}"
        return lanServerUrl
    }

    @JavascriptInterface fun getLocalServerUrl(): String = beamLanServer?.getServerUrl() ?: ""
    @JavascriptInterface fun isRunningServer(): Boolean  = beamLanServer?.isAlive == true

    @JavascriptInterface fun getLocalIp(): String = beamLanServer?.getLocalIp() ?: ""

    // ── LocalSend-style upload ─────────────────────────────────────────────────
    //
    // Called from JavaScript when the user taps a device in the list.
    // After this returns, screen can turn off — the Foreground Service handles
    // the rest (announce → poll → stream files → done).

    @JavascriptInterface
    fun startLocalSendUpload(receiverIp: String): String {
        val uris = storedUris
        if (uris.isEmpty()) return """{"error":"No files selected"}"""
        val fromName = Build.MODEL
        BeamTransferService.startUpload(context, receiverIp, uris, fromName)
        return """{"ok":true,"files":${uris.size}}"""
    }

    /** Returns JSON array of stored file metadata (for displaying to user) */
    @JavascriptInterface
    fun getStoredFileInfo(): String {
        val uris = storedUris
        if (uris.isEmpty()) return "[]"
        val sb = StringBuilder("[")
        uris.forEachIndexed { i, uri ->
            if (i > 0) sb.append(",")
            var name = "file"
            var size = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val ni = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val si = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (ni >= 0) name = cursor.getString(ni) ?: name
                    if (si >= 0) size = cursor.getLong(si)
                }
            }
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            sb.append("""{"name":${jsonStr(name)},"size":$size,"type":${jsonStr(mime)}}""")
        }
        sb.append("]")
        return sb.toString()
    }

    /** How many files are currently staged for send */
    @JavascriptInterface
    fun getStoredFileCount(): Int = storedUris.size

    /** Clear stored URIs after a transfer (or cancel) */
    @JavascriptInterface
    fun clearStoredFiles() { storedUris = emptyList() }

    // ── Accept / decline incoming transfers ────────────────────────────────────

    @JavascriptInterface
    fun acceptIncoming(sessionId: String) {
        beamLanServer?.acceptSession(sessionId)
    }

    @JavascriptInterface
    fun declineIncoming(sessionId: String) {
        beamLanServer?.declineSession(sessionId)
    }

    /** Cancel a receiver-side upload in progress (marks session cancelled → upload stops) */
    @JavascriptInterface
    fun cancelIncomingTransfer(sessionId: String) {
        beamLanServer?.cancelUploadSession(sessionId)
    }

    /** Cancel the currently running sender-side upload */
    @JavascriptInterface
    fun cancelTransfer() {
        BeamTransferService.cancelCurrent()
    }

    /** Cancel a QR browser session download (stops ProgressInputStream mid-stream) */
    @JavascriptInterface
    fun cancelSessionTransfer(sessionId: String) {
        beamLanServer?.cancelSession(sessionId)
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    @JavascriptInterface
    fun showNotification(title: String, body: String) {
        BeamNotificationHelper.showSimple(context, title, body)
    }

    // ── Legacy download (still used by autoConnectIfLanServer) ────────────────

    @JavascriptInterface
    fun acceptTransfer(
        fromName: String,
        filename: String,
        filesize: Long,
        downloadPath: String,
        lanServerHost: String
    ) {
        val fullUrl = "http://$lanServerHost$downloadPath"
        val intent = Intent(context, BeamTransferService::class.java).apply {
            action = BeamTransferService.ACTION_START_DOWNLOAD
            putExtra("fromName",    fromName)
            putExtra("filename",    filename)
            putExtra("filesize",    filesize)
            putExtra("downloadUrl", fullUrl)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(intent)
        else
            context.startService(intent)
    }

    // ── Beam Blaze (Nearby Connections) ───────────────────────────────────────

    /** Start Beam Blaze mode — advertise + discover simultaneously */
    @JavascriptInterface
    fun startBeamBlaze() { blazeCallback?.startBlaze() }

    /** Stop Beam Blaze and release all Nearby resources */
    @JavascriptInterface
    fun stopBeamBlaze() { blazeCallback?.stopBlaze() }

    /**
     * Silently start Blaze advertising when Receive tab opens.
     * Does nothing if Blaze permissions haven't been granted yet — no prompt shown.
     */
    @JavascriptInterface
    fun startBlazeAdvertising() { blazeCallback?.startBlazeReceiveMode() }

    /** Stop silent Blaze advertising when Receive tab is left */
    @JavascriptInterface
    fun stopBlazeAdvertising() { blazeCallback?.stopBlazeReceiveMode() }

    /** Sender: request connection to a discovered endpoint */
    @JavascriptInterface
    fun blazeRequestConnection(endpointId: String) { blazeCallback?.requestBlazeConnection(endpointId) }

    /** Receiver: accept the incoming Blaze connection */
    @JavascriptInterface
    fun acceptBlazeConnection(endpointId: String) { blazeCallback?.acceptBlazeConnection(endpointId) }

    /** Receiver: decline the incoming Blaze connection */
    @JavascriptInterface
    fun declineBlazeConnection(endpointId: String) { blazeCallback?.declineBlazeConnection(endpointId) }

    /** Sender: send the stored file(s) to the connected endpoint */
    @JavascriptInterface
    fun blazeSendFiles(endpointId: String) { blazeCallback?.sendBlazeFiles(endpointId) }

    // Wired up by MainActivity
    var blazeCallback: BlazeHost? = null

    interface BlazeHost {
        fun startBlaze()
        fun stopBlaze()
        fun startBlazeReceiveMode()
        fun stopBlazeReceiveMode()
        fun requestBlazeConnection(endpointId: String)
        fun acceptBlazeConnection(endpointId: String)
        fun declineBlazeConnection(endpointId: String)
        fun sendBlazeFiles(endpointId: String)
    }

    /** Returns stored URIs for Beam Blaze to stream directly (no base64 needed) */
    fun getStoredUris(): List<android.net.Uri> = storedUris

    /**
     * Android sender: link stored URIs to a browser session so the server can
     * stream them directly to the receiver — no temp file copy, no disk space wasted.
     */
    @JavascriptInterface
    fun linkUrisToSession(sessionId: String): Boolean {
        if (storedUris.isEmpty()) return false
        beamLanServer?.linkUrisToSession(sessionId, storedUris)
        return true
    }

    /** Get display name for a URI */
    fun getUriFileName(uri: android.net.Uri): String? = try {
        context.contentResolver.query(uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }

    /** Returns true when the device is currently on a WiFi network. */
    @JavascriptInterface
    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps    = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.type == android.net.ConnectivityManager.TYPE_WIFI
        }
    }

    private fun jsonStr(s: String) =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
