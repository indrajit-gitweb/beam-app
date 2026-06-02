package com.beam.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
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

    // Listen for transfer completion from the Foreground Service
    private val transferReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BeamTransferService.ACTION_TRANSFER_COMPLETE -> {
                    val filename = intent.getStringExtra("filename") ?: ""
                    val savedPath = intent.getStringExtra("savedPath") ?: ""
                    val fromName = intent.getStringExtra("fromName") ?: ""
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
                                    showPopup('✅', 'File Received!', '${filename} saved to Downloads.', 'success', 6000);
                                }
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }
                BeamTransferService.ACTION_TRANSFER_PROGRESS -> {
                    val pct = intent.getIntExtra("pct", 0)
                    val filename = intent.getStringExtra("filename") ?: ""
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onNativeTransferProgress && window.onNativeTransferProgress($pct, '${filename}');",
                            null
                        )
                    }
                }
                BeamTransferService.ACTION_TRANSFER_FAILED -> {
                    val error = intent.getStringExtra("error") ?: "Unknown error"
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onNativeTransferFailed && window.onNativeTransferFailed('${error}');",
                            null
                        )
                    }
                }
                LanDiscovery.ACTION_SERVER_FOUND -> {
                    val url = intent.getStringExtra("url") ?: ""
                    beamWebInterface.setLanServerUrl(url)
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onLanServerFound && window.onLanServerFound('${url}');",
                            null
                        )
                    }
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled silently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView     = findViewById(R.id.webView)
        loadingView = findViewById(R.id.loadingView)

        requestNotificationPermission()
        setupWebView()
        startLanDiscovery()
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

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled          = true
        settings.domStorageEnabled          = true
        settings.allowFileAccess            = true
        settings.allowContentAccess         = true
        settings.mixedContentMode           = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false

        // Allow WebRTC
        WebView.setWebContentsDebuggingEnabled(true) // remove in production

        webView.addJavascriptInterface(beamWebInterface, "BeamNative")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                loadingView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                // Inject native app detection
                webView.evaluateJavascript(
                    "window.__BEAM_NATIVE_ANDROID__ = true;", null
                )
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false // handle all URLs inside the WebView
            }
        }

        webView.webChromeClient = WebChromeClient()

        // Load from bundled assets (works offline, no LAN server needed for UI)
        webView.loadUrl("file:///android_asset/index.html")
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
