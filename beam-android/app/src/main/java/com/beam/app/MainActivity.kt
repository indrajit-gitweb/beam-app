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
import android.view.WindowManager
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

    // ── File chooser callback — set when WebView requests a file picker ──────
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // Launches the system file picker and returns result to WebView
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        fileChooserCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                ?: emptyArray()
        )
        fileChooserCallback = null
    }

    // ── Listen for transfer completion from the Foreground Service ───────────
    private val transferReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BeamTransferService.ACTION_TRANSFER_COMPLETE -> {
                    val filename  = intent.getStringExtra("filename")  ?: ""
                    val savedPath = intent.getStringExtra("savedPath") ?: ""
                    val fromName  = intent.getStringExtra("fromName")  ?: ""
                    runOnUiThread {
                        webView.evaluateJavascript(
                            """
                            (function() {
                                if (typeof window.onNativeTransferComplete === 'function') {
                                    window.onNativeTransferComplete(
                                        ${kotlinJsonEscape(filename)},
                                        ${kotlinJsonEscape(savedPath)},
                                        ${kotlinJsonEscape(fromName)}
                                    );
                                }
                                if (typeof showPopup === 'function') {
                                    showPopup('✅', 'File Received!',
                                        '${filename} saved to Downloads.', 'success', 6000);
                                }
                            })();
                            """.trimIndent(), null
                        )
                    }
                }
                BeamTransferService.ACTION_TRANSFER_PROGRESS -> {
                    val pct      = intent.getIntExtra("pct", 0)
                    val filename = intent.getStringExtra("filename") ?: ""
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onNativeTransferProgress && " +
                            "window.onNativeTransferProgress($pct, '${filename}');", null
                        )
                    }
                }
                BeamTransferService.ACTION_TRANSFER_FAILED -> {
                    val error = intent.getStringExtra("error") ?: "Unknown error"
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onNativeTransferFailed && " +
                            "window.onNativeTransferFailed('${error}');", null
                        )
                    }
                }
                LanDiscovery.ACTION_SERVER_FOUND -> {
                    val url = intent.getStringExtra("url") ?: ""
                    beamWebInterface.setLanServerUrl(url)
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onLanServerFound && window.onLanServerFound('${url}');", null
                        )
                    }
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled silently */ }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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

        WebView.setWebContentsDebuggingEnabled(true) // disable before Play Store release

        webView.addJavascriptInterface(beamWebInterface, "BeamNative")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                loadingView.visibility = View.GONE
                webView.visibility     = View.VISIBLE
                webView.evaluateJavascript("window.__BEAM_NATIVE_ANDROID__ = true;", null)
            }
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = false
        }

        // ── WebChromeClient with file chooser support ─────────────────────────
        // Without overriding onShowFileChooser, tapping the drop zone on Android
        // does nothing — the file picker never opens.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // Cancel any previous pending callback
                fileChooserCallback?.onReceiveValue(emptyArray())
                fileChooserCallback = filePathCallback

                return try {
                    filePickerLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (e: Exception) {
                    fileChooserCallback = null
                    false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun startLanServer() {
        try {
            val name   = android.os.Build.MODEL
            val server = BeamLanServer(this, name)
            server.start()
            beamWebInterface.setLanServer(server)
            // Announce via NSD so other Beam devices auto-discover this phone
            lanDiscovery.registerService(BeamLanServer.PORT, name)
            beamLanServer = server
            Log.d("MainActivity", "LAN server started: ${server.getServerUrl()}")

            // Acquire WakeLock + request battery optimization exemption
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Beam::LanServerWakeLock"
            ).apply { acquire(60 * 60 * 1000L) }
            Log.d("MainActivity", "WakeLock acquired")

            // Request battery optimization exemption — prevents Realme/OPPO
            // OplusProxyWakeLock from force-releasing our WakeLock
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = android.content.Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w("MainActivity", "Cannot request battery exemption: ${e.message}")
                }
            }
            // Inject server URL into WebView once page loads
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
            addAction(BeamTransferService.ACTION_TRANSFER_COMPLETE)
            addAction(BeamTransferService.ACTION_TRANSFER_PROGRESS)
            addAction(BeamTransferService.ACTION_TRANSFER_FAILED)
            addAction(LanDiscovery.ACTION_SERVER_FOUND)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transferReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(transferReceiver, filter)
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        beamLanServer?.stop()
        beamLanServer = null
        super.onDestroy()
        unregisterReceiver(transferReceiver)
        lanDiscovery.stopDiscovery()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    companion object {
        fun kotlinJsonEscape(s: String): String =
            "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
}
