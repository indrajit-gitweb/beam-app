package com.beam.app

import android.app.Service
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BeamTransferService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        // Show foreground notification immediately (required for Android 8+)
        val notifTitle = when (action) {
            ACTION_START_DOWNLOAD -> "Beam — Receiving ${intent.getStringExtra("filename") ?: "file"}"
            ACTION_START_UPLOAD   -> "Beam — Sending ${intent.getStringExtra("filename") ?: "file"}"
            else -> "Beam"
        }
        startForeground(NOTIFICATION_ID, BeamNotificationHelper.buildProgress(this, notifTitle, 0))

        // Acquire wake lock — keeps CPU running when screen is off
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Beam::TransferWakeLock").apply {
            acquire(10 * 60 * 1000L) // max 10 minutes
        }

        when (action) {
            ACTION_START_DOWNLOAD -> handleDownload(intent)
            ACTION_START_UPLOAD   -> handleUpload(intent)
        }

        return START_NOT_STICKY
    }

    // ── Download (receive file from LAN server) ────────────────────────────────

    private fun handleDownload(intent: Intent) {
        val fromName    = intent.getStringExtra("fromName")    ?: "sender"
        val filename    = intent.getStringExtra("filename")    ?: "beam-file"
        val downloadUrl = intent.getStringExtra("downloadUrl") ?: return

        serviceScope.launch {
            try {
                val request  = Request.Builder().url(downloadUrl).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    broadcastFailure("Server returned ${response.code}")
                    return@launch
                }

                val body = response.body ?: run { broadcastFailure("Empty response"); return@launch }
                val totalBytes = body.contentLength()
                val savedFile  = saveToDownloads(filename, body, totalBytes)

                broadcastComplete(filename, savedFile.absolutePath, fromName)
                BeamNotificationHelper.showComplete(
                    this@BeamTransferService,
                    "Received $filename",
                    "From $fromName — saved to Downloads"
                )

            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                broadcastFailure(e.message ?: "Download failed")
                BeamNotificationHelper.showError(
                    this@BeamTransferService,
                    "Transfer failed",
                    e.message ?: "Download error"
                )
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ── Upload (send file to LAN server) ──────────────────────────────────────

    private fun handleUpload(intent: Intent) {
        val fileBase64   = intent.getStringExtra("fileBase64")   ?: return
        val filename     = intent.getStringExtra("filename")     ?: "beam-file"
        val mimeType     = intent.getStringExtra("mimeType")     ?: "application/octet-stream"
        val filesize     = intent.getLongExtra("filesize", 0L)
        val targetPeerId = intent.getStringExtra("targetPeerId") ?: ""
        val fromPeerId   = intent.getStringExtra("fromPeerId")   ?: ""
        val fromName     = intent.getStringExtra("fromName")     ?: android.os.Build.MODEL
        val uploadUrl    = intent.getStringExtra("uploadUrl")    ?: return

        serviceScope.launch {
            try {
                val fileBytes = Base64.decode(fileBase64, Base64.DEFAULT)
                val body      = fileBytes.toRequestBody(mimeType.toMediaType())

                val request = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("X-Filename",    URLEncoder.encode(filename, "UTF-8"))
                    .addHeader("X-Filesize",    filesize.toString())
                    .addHeader("X-Filetype",    mimeType)
                    .addHeader("X-Target-Peer", targetPeerId)
                    .addHeader("X-From-Peer",   fromPeerId)
                    .addHeader("X-From-Name",   URLEncoder.encode(fromName, "UTF-8"))
                    .addHeader("Content-Type",  "application/octet-stream")
                    .post(body)
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    broadcastComplete(filename, "", fromName)
                    BeamNotificationHelper.showComplete(
                        this@BeamTransferService,
                        "Sent $filename",
                        "Delivered successfully"
                    )
                } else {
                    broadcastFailure("Server error ${response.code}")
                    BeamNotificationHelper.showError(
                        this@BeamTransferService,
                        "Send failed",
                        "Server error ${response.code}"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                broadcastFailure(e.message ?: "Upload failed")
                BeamNotificationHelper.showError(
                    this@BeamTransferService,
                    "Send failed",
                    e.message ?: "Upload error"
                )
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ── File saving ────────────────────────────────────────────────────────────

    private fun saveToDownloads(filename: String, body: ResponseBody, totalBytes: Long): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val safeFilename = filename.replace(Regex("[/\\\\?%*:|\"<>]"), "_")
        var destFile = File(downloadsDir, safeFilename)

        // Avoid overwriting existing files
        var i = 1
        while (destFile.exists()) {
            val ext  = if (safeFilename.contains('.')) ".${safeFilename.substringAfterLast('.')}" else ""
            val base = if (safeFilename.contains('.')) safeFilename.substringBeforeLast('.') else safeFilename
            destFile = File(downloadsDir, "${base}_($i)$ext")
            i++
        }

        val inputStream  = body.byteStream()
        val outputStream = FileOutputStream(destFile)
        val buffer       = ByteArray(8192)
        var bytesRead: Int
        var totalRead    = 0L

        try {
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalBytes > 0) {
                    val pct = (totalRead * 100 / totalBytes).toInt()
                    broadcastProgress(pct, destFile.name)
                    BeamNotificationHelper.updateProgress(this, "Receiving ${destFile.name}…", pct)
                }
            }
        } finally {
            outputStream.flush()
            outputStream.close()
            inputStream.close()
        }

        return destFile
    }

    // ── Broadcasts ────────────────────────────────────────────────────────────

    private fun broadcastComplete(filename: String, savedPath: String, fromName: String) {
        sendBroadcast(Intent(ACTION_TRANSFER_COMPLETE).apply {
            putExtra("filename",  filename)
            putExtra("savedPath", savedPath)
            putExtra("fromName",  fromName)
        })
    }

    private fun broadcastProgress(pct: Int, filename: String) {
        sendBroadcast(Intent(ACTION_TRANSFER_PROGRESS).apply {
            putExtra("pct",      pct)
            putExtra("filename", filename)
        })
    }

    private fun broadcastFailure(error: String) {
        sendBroadcast(Intent(ACTION_TRANSFER_FAILED).apply {
            putExtra("error", error)
        })
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
    }

    companion object {
        const val TAG                      = "BeamTransferService"
        const val NOTIFICATION_ID          = 1001
        const val ACTION_START_DOWNLOAD    = "com.beam.app.START_DOWNLOAD"
        const val ACTION_START_UPLOAD      = "com.beam.app.START_UPLOAD"
        const val ACTION_TRANSFER_COMPLETE = "com.beam.app.TRANSFER_COMPLETE"
        const val ACTION_TRANSFER_PROGRESS = "com.beam.app.TRANSFER_PROGRESS"
        const val ACTION_TRANSFER_FAILED   = "com.beam.app.TRANSFER_FAILED"
    }
}
