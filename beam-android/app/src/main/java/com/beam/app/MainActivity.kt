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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingView: LinearLayout
    private lateinit var lanDiscovery: LanDiscovery
    private lateinit var beamWebInterface: BeamWebInterface
    private var beamLanServer: BeamLanServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

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

    companion object {
        private fun jsonStr(s: String) =
            "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n","\\n")}\""
    }
}
