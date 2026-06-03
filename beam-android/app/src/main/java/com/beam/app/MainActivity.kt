package com.beam.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BeamWebInterface.BlazeHost {

    private lateinit var webView: WebView
    private lateinit var loadingView: LinearLayout
    private lateinit var lanDiscovery: LanDiscovery
    private lateinit var beamWebInterface: BeamWebInterface
    private var beamLanServer: BeamLanServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Beam Blaze (Nearby Connections) ───────────────────────────────────────
    private var beamBlaze: BeamBlazeManager? = null

    // Permissions needed for Beam Blaze — requested as two grouped dialogs:
    //   Dialog 1: "Nearby devices" → covers all Bluetooth permissions
    //   Dialog 2: "Location"       → only on Android 9-11 (OS mandate)
    private val blazePermissions: Array<String> get() {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.BLUETOOTH
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list += "android.permission.NEARBY_WIFI_DEVICES"
        }
        return list.toTypedArray()
    }

    private val blazePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            actuallyStartBlaze()
        } else {
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onBlazePermissionDenied && window.onBlazePermissionDenied();", null
                )
            }
        }
    }

    // ── File chooser ──────────────────────────────────────────────────────────
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = extractUris(result.resultCode, result.data)
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
        // Store for native background upload — avoids loading into JS memory
        if (uris.isNotEmpty()) beamWebInterface.storeSelectedUris(uris)
        Log.d("MainActivity", "File picker returned ${uris.size} URI(s)")
    }

    /**
     * Extract URIs from a file-picker result, handling both single and multi-select.
     *
     * WebChromeClient.FileChooserParams.parseResult() checks getData() first —
     * if it returns even one URI it stops there and ignores getClipData(), so
     * only the first file comes through when the user picks several.
     * Android photo-picker and Files app both put multi-select results in
     * ClipData, so we check that first.
     */
    private fun extractUris(resultCode: Int, data: android.content.Intent?): Array<Uri> {
        if (data == null || resultCode != android.app.Activity.RESULT_OK) return emptyArray()

        // Multiple files → ClipData (photo picker, Files app with multi-select)
        data.clipData?.let { clip ->
            if (clip.itemCount > 0) {
                Log.d("MainActivity", "extractUris: ClipData has ${clip.itemCount} items")
                return Array(clip.itemCount) { clip.getItemAt(it).uri }
            }
        }

        // Single file → getData()
        data.data?.let { uri ->
            Log.d("MainActivity", "extractUris: single getData() URI")
            return arrayOf(uri)
        }

        return emptyArray()
    }

    // ── Broadcast receiver ─────────────────────────────────────────────────────
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                // ── Incoming transfer request from another device ─────────────
                BeamLanServer.ACTION_INCOMING_REQUEST -> {
                    val sessionId  = intent.getStringExtra("sessionId")  ?: return
                    val senderName = intent.getStringExtra("senderName") ?: "Unknown"
                    val fileName   = intent.getStringExtra("fileName")   ?: ""
                    val fileSize   = intent.getLongExtra("fileSize", 0L)
                    val fileCount  = intent.getIntExtra("fileCount", 1)
                    val totalSize  = intent.getLongExtra("totalSize", fileSize)

                    runOnUiThread {
                        // Call the JavaScript callback to show the Accept/Decline popup
                        val js = """
                            (function() {
                                if (typeof window.onNativeIncomingRequest === 'function') {
                                    window.onNativeIncomingRequest(
                                        ${jsonStr(sessionId)},
                                        ${jsonStr(senderName)},
                                        ${jsonStr(fileName)},
                                        $fileSize,
                                        $fileCount,
                                        $totalSize
                                    );
                                }
                            })();
                        """.trimIndent()
                        webView.evaluateJavascript(js, null)
                    }

                    // Also show a system notification in case the app is in background
                    BeamNotificationHelper.showSimple(
                        context,
                        "📥 Incoming from $senderName",
                        "Wants to send ${if (fileCount > 1) "$fileCount files" else fileName} — open Beam to accept"
                    )
                }

                // ── Transfer progress ─────────────────────────────────────────
                BeamTransferService.ACTION_TRANSFER_PROGRESS -> {
                    val pct   = intent.getIntExtra("pct", 0)
                    val fname = intent.getStringExtra("filename") ?: ""
                    val bytes = intent.getLongExtra("bytesTransferred", 0L)
                    val total = intent.getLongExtra("totalBytes", 0L)
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onNativeTransferProgress && " +
                            "window.onNativeTransferProgress($pct,${jsonStr(fname)},$bytes,$total);", null
                        )
                    }
                }

                // ── Transfer complete ──────────────────────────────────────────
                BeamTransferService.ACTION_TRANSFER_COMPLETE -> {
                    val filename  = intent.getStringExtra("filename")  ?: ""
                    val savedPath = intent.getStringExtra("savedPath") ?: ""
                    val fromName  = intent.getStringExtra("fromName")  ?: ""
                    runOnUiThread {
                        webView.evaluateJavascript(
                            """(function(){
                                window.onNativeTransferComplete && window.onNativeTransferComplete(
                                    ${jsonStr(filename)}, ${jsonStr(savedPath)}, ${jsonStr(fromName)});
                                typeof showPopup === 'function' && showPopup(
                                    '✅','File Received!','${filename} saved to Downloads.','success',6000);
                            })();""", null
                        )
                    }
                }

                // ── Transfer failed ───────────────────────────────────────────
                BeamTransferService.ACTION_TRANSFER_FAILED -> {
                    val error = intent.getStringExtra("error") ?: "Unknown error"
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onNativeTransferFailed && " +
                            "window.onNativeTransferFailed(${jsonStr(error)});", null
                        )
                    }
                }

                // ── Transfer cancelled ────────────────────────────────────────
                BeamTransferService.ACTION_TRANSFER_CANCELLED -> {
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onNativeTransferCancelled && " +
                            "window.onNativeTransferCancelled();", null
                        )
                    }
                }

                // ── LAN server discovered via NSD ─────────────────────────────
                LanDiscovery.ACTION_SERVER_FOUND -> {
                    val url = intent.getStringExtra("url") ?: return
                    beamWebInterface.setLanServerUrl(url)
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onLanServerFound && window.onLanServerFound(${jsonStr(url)});", null
                        )
                    }
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled silently */ }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView     = findViewById(R.id.webView)
        loadingView = findViewById(R.id.loadingView)

        requestNotificationPermission()
        startLanDiscovery()
        setupWebView()
        registerReceivers()

        // Handle files shared INTO Beam from another app (share sheet)
        handleShareIntent(intent)
    }

    // Called when Beam is already running and a new share intent arrives
    // (android:launchMode="singleTask" routes it here instead of onCreate)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Handles ACTION_SEND / ACTION_SEND_MULTIPLE intents that arrive when the
     * user picks Beam from the system share sheet.
     * Extracts the shared file URI(s), stores them in BeamWebInterface, and
     * once the WebView is ready injects them into the file queue.
     */
    @Suppress("DEPRECATION")
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return

        val uris = mutableListOf<Uri>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (uri != null) uris.add(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                if (!list.isNullOrEmpty()) uris.addAll(list)
            }
            else -> return  // not a share intent
        }

        if (uris.isEmpty()) return

        Log.d("MainActivity", "Share intent received: ${uris.size} file(s)")
        beamWebInterface.storeSelectedUris(uris.toTypedArray())

        // Build JSON metadata for each file so JavaScript can render the queue
        val filesJson = buildFilesJson(uris.toTypedArray())

        // The WebView may not be ready yet — retry until onPageFinished fires
        pendingShareJson = filesJson
        injectShareFilesIfReady()
    }

    private var pendingShareJson: String? = null
    private var webViewReady = false

    private fun injectShareFilesIfReady() {
        val json = pendingShareJson ?: return
        if (!webViewReady) return   // will be called again from onPageFinished
        pendingShareJson = null
        runOnUiThread {
            webView.evaluateJavascript(
                "window.__beamShareFiles && window.__beamShareFiles($json);", null
            )
        }
    }

    /** Read display-name + size + MIME type for each URI via ContentResolver. */
    private fun buildFilesJson(uris: Array<Uri>): String {
        val arr = org.json.JSONArray()
        uris.forEach { uri ->
            try {
                var name = "file"
                var size = 0L
                contentResolver.query(
                    uri,
                    arrayOf(
                        android.provider.OpenableColumns.DISPLAY_NAME,
                        android.provider.OpenableColumns.SIZE
                    ), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val ni = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val si = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (ni >= 0) name = cursor.getString(ni) ?: "file"
                        if (si >= 0) size = cursor.getLong(si)
                    }
                }
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                arr.put(org.json.JSONObject().apply {
                    put("name", name)
                    put("size", size)
                    put("type", mime)
                })
            } catch (e: Exception) {
                Log.e("MainActivity", "buildFilesJson error for $uri: ${e.message}")
            }
        }
        return arr.toString()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupWebView() {
        beamWebInterface = BeamWebInterface(this)
        beamWebInterface.blazeCallback = this   // wire up Beam Blaze
        startLanServer()

        with(webView.settings) {
            javaScriptEnabled               = true
            domStorageEnabled               = true
            allowFileAccess                 = true
            allowContentAccess              = true
            mixedContentMode                = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }

        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(beamWebInterface, "BeamNative")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                loadingView.visibility = View.GONE
                webView.visibility     = View.VISIBLE
                webView.evaluateJavascript("window.__BEAM_NATIVE_ANDROID__ = true;", null)
                webViewReady = true
                injectShareFilesIfReady()   // deliver any pending share intent
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url  = req.url.toString()
                val host = req.url.host ?: ""

                // Keep local/internal URLs inside the WebView
                if (url.startsWith("file://") ||
                    host == "localhost" ||
                    host == "127.0.0.1" ||
                    host.matches(Regex("^192\\.168\\..*")) ||
                    host.matches(Regex("^10\\..*")) ||
                    host.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*"))) {
                    return false
                }

                // Open all external URLs with Android's Intent system so
                // WhatsApp, Telegram, Gmail and mail clients launch properly.
                // The WebView stays on the Beam page — user comes back to the
                // share screen intact after switching to the other app.
                return try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, req.url))
                    true
                } catch (e: Exception) {
                    Log.w("MainActivity", "Cannot open external URL: $url — ${e.message}")
                    false  // fall back to WebView if no app handles it
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(emptyArray())
                fileChooserCallback = filePathCallback
                return try {
                    // Explicitly enable multi-select — createIntent() alone does
                    // not set EXTRA_ALLOW_MULTIPLE even when the HTML <input>
                    // has the `multiple` attribute, so without this flag the
                    // system picker opens in single-file mode.
                    val intent = fileChooserParams.createIntent().apply {
                        putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    filePickerLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    fileChooserCallback = null; false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun startLanServer() {
        try {
            val server = BeamLanServer(this, android.os.Build.MODEL)
            server.start()
            beamWebInterface.setLanServer(server)
            lanDiscovery.registerService(BeamLanServer.PORT, android.os.Build.MODEL)
            beamLanServer = server
            Log.d("MainActivity", "LAN server: ${server.getServerUrl()}")

            // WakeLock — keeps CPU alive for the server thread
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Beam::LanServerWakeLock")
                .apply { acquire(60 * 60 * 1000L) }

            // Battery optimization exemption — prevents Realme/OPPO from freezing us
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                } catch (e: Exception) {
                    Log.w("MainActivity", "Battery exemption unavailable: ${e.message}")
                }
            }

            webView.evaluateJavascript(
                "window.__BEAM_LOCAL_SERVER_URL__ = '${server.getServerUrl()}';", null
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start LAN server", e)
        }
    }

    private fun startLanDiscovery() {
        lanDiscovery = LanDiscovery(this)
        lanDiscovery.startDiscovery()
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BeamLanServer.ACTION_INCOMING_REQUEST)
            addAction(BeamTransferService.ACTION_TRANSFER_COMPLETE)
            addAction(BeamTransferService.ACTION_TRANSFER_PROGRESS)
            addAction(BeamTransferService.ACTION_TRANSFER_FAILED)
            addAction(BeamTransferService.ACTION_TRANSFER_CANCELLED)
            addAction(LanDiscovery.ACTION_SERVER_FOUND)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(broadcastReceiver, filter)
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        beamLanServer?.stop()
        beamLanServer = null
        unregisterReceiver(broadcastReceiver)
        lanDiscovery.stopDiscovery()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // ── BeamWebInterface.BlazeHost implementation ─────────────────────────────

    override fun startBlaze() {
        val missing = blazePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            actuallyStartBlaze()
        } else {
            blazePermLauncher.launch(missing.toTypedArray())
        }
    }

    private fun actuallyStartBlaze() {
        beamBlaze?.stop()
        val deviceName = android.os.Build.MODEL
        beamBlaze = BeamBlazeManager(this, deviceName)
        beamBlaze!!.start(blazeCb)
        runOnUiThread {
            webView.evaluateJavascript(
                "window.onBlazeStarted && window.onBlazeStarted();", null
            )
        }
    }

    override fun stopBlaze() {
        beamBlaze?.stop()
        beamBlaze = null
    }

    override fun requestBlazeConnection(endpointId: String) {
        beamBlaze?.requestConnection(endpointId)
    }

    override fun acceptBlazeConnection(endpointId: String) {
        beamBlaze?.acceptConnection(endpointId)
    }

    override fun declineBlazeConnection(endpointId: String) {
        beamBlaze?.declineConnection(endpointId)
    }

    override fun sendBlazeFiles(endpointId: String) {
        val uris = beamWebInterface.getStoredUris()
        if (uris.isEmpty()) {
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onBlazeError && window.onBlazeError('No files selected');", null
                )
            }
            return
        }
        // Send each stored file via Beam Blaze
        uris.forEachIndexed { idx, uri ->
            val name = beamWebInterface.getUriFileName(uri) ?: "file_$idx"
            beamBlaze?.sendFile(endpointId, android.net.Uri.parse(uri.toString()), name)
        }
    }

    // ── Beam Blaze callbacks → JavaScript ─────────────────────────────────────

    private val blazeCb = object : BeamBlazeManager.BlazeCallback {
        override fun onDeviceFound(endpointId: String, name: String) = runJs(
            "window.onBlazeDeviceFound && window.onBlazeDeviceFound(${q(endpointId)},${q(name)});"
        )
        override fun onDeviceLost(endpointId: String) = runJs(
            "window.onBlazeDeviceLost && window.onBlazeDeviceLost(${q(endpointId)});"
        )
        override fun onIncomingConnection(endpointId: String, name: String) = runJs(
            "window.onBlazeIncoming && window.onBlazeIncoming(${q(endpointId)},${q(name)});"
        )
        override fun onConnected(endpointId: String, name: String) = runJs(
            "window.onBlazeConnected && window.onBlazeConnected(${q(endpointId)},${q(name)});"
        )
        override fun onDisconnected(endpointId: String) = runJs(
            "window.onBlazeDisconnected && window.onBlazeDisconnected(${q(endpointId)});"
        )
        override fun onTransferProgress(pct: Int, filename: String, bytes: Long, total: Long) = runJs(
            "window.onBlazeProgress && window.onBlazeProgress($pct,${q(filename)},$bytes,$total);"
        )
        override fun onTransferComplete(filename: String) = runJs(
            "window.onBlazeComplete && window.onBlazeComplete(${q(filename)});"
        )
        override fun onTransferFailed(error: String) = runJs(
            "window.onBlazeError && window.onBlazeError(${q(error)});"
        )
        override fun onError(message: String) = runJs(
            "window.onBlazeError && window.onBlazeError(${q(message)});"
        )
    }

    /** Evaluate a JS string on the main thread */
    private fun runJs(script: String) {
        runOnUiThread { webView.evaluateJavascript(script, null) }
    }
    /** Quote a Kotlin string as a JS string literal */
    private fun q(s: String) = "\"${s.replace("\\","\\\\").replace("\"","\\\"")}\""

    companion object {
        private fun jsonStr(s: String) =
            "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n","\\n")}\""
    }
}
