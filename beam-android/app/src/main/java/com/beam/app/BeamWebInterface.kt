package com.beam.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.JavascriptInterface

class BeamWebInterface(private val context: Context) {

    @Volatile private var lanServerUrl: String = ""
    private var beamLanServer: BeamLanServer? = null

    fun setLanServerUrl(url: String) { lanServerUrl = url }
    fun setLanServer(server: BeamLanServer?) { beamLanServer = server }

    @JavascriptInterface
    fun isNativeApp(): Boolean = true

    @JavascriptInterface
    fun getPlatform(): String = "android"

    @JavascriptInterface
    fun getDeviceName(): String = Build.MODEL

    @JavascriptInterface
    fun getDeviceType(): String = "phone"

    @JavascriptInterface
    fun getLanServerUrl(): String {
        // Prefer own server if running — connect via localhost
        if (beamLanServer?.isAlive == true) return "http://localhost:${BeamLanServer.PORT}"
        return lanServerUrl
    }

    /** Returns the URL of the LAN server this device is running (as the hub). */
    @JavascriptInterface
    fun getLocalServerUrl(): String = beamLanServer?.getServerUrl() ?: ""

    /** True when this Android device is running its own LAN server. */
    @JavascriptInterface
    fun isRunningServer(): Boolean = beamLanServer?.isAlive == true

    // Called when user taps Accept on an incoming transfer
    // downloadUrl: the /download/:id path on the LAN server
    // lanServerHost: e.g. "192.168.1.5:7777"
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    // Called when user initiates a send from the web UI
    // fileBase64: base64-encoded file content
    @JavascriptInterface
    fun startUpload(
        fileBase64: String,
        filename: String,
        mimeType: String,
        filesize: Long,
        targetPeerId: String,
        fromPeerId: String,
        fromName: String,
        lanServerHost: String
    ) {
        val intent = Intent(context, BeamTransferService::class.java).apply {
            action = BeamTransferService.ACTION_START_UPLOAD
            putExtra("fileBase64",   fileBase64)
            putExtra("filename",     filename)
            putExtra("mimeType",     mimeType)
            putExtra("filesize",     filesize)
            putExtra("targetPeerId", targetPeerId)
            putExtra("fromPeerId",   fromPeerId)
            putExtra("fromName",     fromName)
            putExtra("uploadUrl",    "http://$lanServerHost/upload")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    @JavascriptInterface
    fun showNotification(title: String, body: String) {
        BeamNotificationHelper.showSimple(context, title, body)
    }

    /**
     * Opens the ZXing QR code scanner.
     * When a QR code is scanned, MainActivity calls
     * window.connectFromQrCode(url) with the scanned URL.
     * Call from JavaScript: BeamNative.scanQrCode()
     */
    @JavascriptInterface
    fun scanQrCode() {
        if (context is MainActivity) {
            (context as MainActivity).launchQrScanner()
        }
    }
}
