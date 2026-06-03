package com.beam.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BeamTransferService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private val http = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        val label = when (action) {
            ACTION_LOCAL_SEND_UPLOAD -> "Beam — Sending…"
            ACTION_START_DOWNLOAD    -> "Beam — Receiving ${intent.getStringExtra("filename") ?: "file"}"
            else -> "Beam"
        }
        startForeground(NOTIFICATION_ID,
            BeamNotificationHelper.buildProgress(this, label, 0))

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Beam::TransferWakeLock")
            .apply { acquire(30 * 60 * 1000L) }   // 30 min max

        when (action) {
            ACTION_LOCAL_SEND_UPLOAD -> handleLocalSendUpload(intent)
            ACTION_START_DOWNLOAD    -> handleDownload(intent)
        }
        return START_NOT_STICKY
    }

    // ── LocalSend-style upload: announce → poll acceptance → stream files ──────
    //
    // Works entirely in native code — screen can turn off after the user taps
    // "Send" and this service will keep running.

    private fun handleLocalSendUpload(intent: Intent) {
        val receiverIp  = intent.getStringExtra("receiverIp")  ?: return
        val uriStrings  = intent.getStringArrayListExtra("uriStrings")?.toTypedArray() ?: return
        val fromName    = intent.getStringExtra("fromName")    ?: android.os.Build.MODEL

        scope.launch {
            try {
                // ── 1. Resolve file metadata from content URIs ────────────────
                data class FileEntry(val uri: Uri, val name: String, val size: Long, val mime: String)

                val files = uriStrings.mapNotNull { uriString ->
                    val uri  = Uri.parse(uriString)
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    var name = "beam-file"
                    var size = 0L
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (ni >= 0) name = cursor.getString(ni) ?: name
                            if (si >= 0) size = cursor.getLong(si)
                        }
                    }
                    FileEntry(uri, name, size, mime)
                }

                if (files.isEmpty()) {
                    broadcastFailure("No files to send")
                    return@launch
                }

                // ── 2. POST /send-request to announce ─────────────────────────
                val announceBody = buildString {
                    append("""{"senderName":${jsonStr(fromName)},"files":[""")
                    files.forEachIndexed { i, f ->
                        if (i > 0) append(",")
                        append("""{"name":${jsonStr(f.name)},"size":${f.size},"type":${jsonStr(f.mime)}}""")
                    }
                    append("]}")
                }

                val announceResp = http.newCall(
                    Request.Builder()
                        .url("http://$receiverIp:${BeamLanServer.PORT}/send-request")
                        .post(announceBody.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute()

                if (!announceResp.isSuccessful) {
                    broadcastFailure("Receiver not responding (${announceResp.code})")
                    return@launch
                }

                val annJson   = org.json.JSONObject(announceResp.body?.string() ?: "{}")
                val sessionId = annJson.optString("sessionId", "")
                if (sessionId.isEmpty()) {
                    broadcastFailure("No session ID from receiver")
                    return@launch
                }

                Log.d(TAG, "Session $sessionId — waiting for acceptance…")
                BeamNotificationHelper.updateProgress(this@BeamTransferService, "Waiting for acceptance…", 0)

                // ── 3. Poll /status until accepted or declined (max 90 s) ──────
                var accepted = false
                for (tick in 0..180) {
                    val statusResp = try {
                        http.newCall(
                            Request.Builder()
                                .url("http://$receiverIp:${BeamLanServer.PORT}/status/$sessionId")
                                .build()
                        ).execute()
                    } catch (_: Exception) { delay(500); continue }

                    val status = try {
                        org.json.JSONObject(statusResp.body?.string() ?: "{}").optString("status")
                    } catch (_: Exception) { "pending" }

                    when (status) {
                        "accepted" -> { accepted = true; break }
                        "declined" -> {
                            broadcastFailure("Transfer declined by receiver")
                            return@launch
                        }
                    }
                    delay(500)
                }

                if (!accepted) {
                    broadcastFailure("No response from receiver (timeout)")
                    return@launch
                }

                Log.d(TAG, "Accepted! Uploading ${files.size} file(s)…")

                // ── 4. Stream each file to /upload ─────────────────────────────
                files.forEachIndexed { idx, file ->
                    BeamNotificationHelper.updateProgress(
                        this@BeamTransferService,
                        "Sending ${file.name} (${idx + 1}/${files.size})…", 0
                    )

                    val uri  = file.uri
                    val mime = file.mime
                    val size = file.size

                    val reqBody = object : RequestBody() {
                        override fun contentType() = mime.toMediaType()
                        override fun contentLength() = size
                        override fun writeTo(sink: BufferedSink) {
                            val input = contentResolver.openInputStream(uri) ?: return
                            val buf = ByteArray(65_536)
                            var n: Int
                            var sent = 0L
                            try {
                                while (input.read(buf).also { n = it } != -1) {
                                    sink.write(buf, 0, n)
                                    sent += n
                                    val pct = if (size > 0) (sent * 100 / size).toInt() else 0
                                    broadcastProgress(pct, file.name)
                                    BeamNotificationHelper.updateProgress(
                                        this@BeamTransferService, "Sending ${file.name}…", pct
                                    )
                                }
                            } finally { input.close() }
                        }
                    }

                    val uploadResp = http.newCall(
                        Request.Builder()
                            .url("http://$receiverIp:${BeamLanServer.PORT}/upload")
                            .addHeader("X-Filename",    URLEncoder.encode(file.name, "UTF-8"))
                            .addHeader("X-Filesize",    file.size.toString())
                            .addHeader("X-Filetype",    mime)
                            .addHeader("X-From-Name",   URLEncoder.encode(fromName, "UTF-8"))
                            .addHeader("X-Session-Id",  sessionId)
                            .addHeader("X-File-Index",  idx.toString())
                            .addHeader("X-Total-Files", files.size.toString())
                            .post(reqBody)
                            .build()
                    ).execute()

                    if (!uploadResp.isSuccessful) {
                        broadcastFailure("Upload failed for ${file.name}: ${uploadResp.code}")
                        return@launch
                    }
                    Log.d(TAG, "Sent: ${file.name}")
                }

                // ── 5. All done ────────────────────────────────────────────────
                broadcastComplete(files.joinToString(", ") { it.name }, "", fromName)
                BeamNotificationHelper.showComplete(
                    this@BeamTransferService,
                    "Sent ${files.size} file(s)",
                    "Delivered to $receiverIp"
                )

            } catch (e: Exception) {
                Log.e(TAG, "Local send failed", e)
                broadcastFailure(e.message ?: "Upload error")
                BeamNotificationHelper.showError(this@BeamTransferService, "Send failed", e.message ?: "")
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ── Download (receive file from another device's /upload endpoint) ─────────

    private fun handleDownload(intent: Intent) {
        val fromName    = intent.getStringExtra("fromName")    ?: "sender"
        val filename    = intent.getStringExtra("filename")    ?: "beam-file"
        val downloadUrl = intent.getStringExtra("downloadUrl") ?: return

        scope.launch {
            try {
                val resp = http.newCall(Request.Builder().url(downloadUrl).build()).execute()
                if (!resp.isSuccessful) { broadcastFailure("Server returned ${resp.code}"); return@launch }
                val body = resp.body ?: run { broadcastFailure("Empty response"); return@launch }
                val saved = saveToDownloads(filename, body, body.contentLength())
                broadcastComplete(filename, saved.absolutePath, fromName)
                BeamNotificationHelper.showComplete(this@BeamTransferService,
                    "Received $filename", "From $fromName — saved to Downloads")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                broadcastFailure(e.message ?: "Download failed")
                BeamNotificationHelper.showError(this@BeamTransferService, "Receive failed", e.message ?: "")
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ── File saving ────────────────────────────────────────────────────────────

    private fun saveToDownloads(filename: String, body: ResponseBody, totalBytes: Long): File {
        val dir  = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val safe = filename.replace(Regex("[/\\\\?%*:|\"<>]"), "_")
        var dest = File(dir, safe)
        var i = 1
        while (dest.exists()) {
            val ext  = if (safe.contains('.')) ".${safe.substringAfterLast('.')}" else ""
            val base = if (safe.contains('.')) safe.substringBeforeLast('.') else safe
            dest = File(dir, "${base}_($i)$ext"); i++
        }
        val fos = FileOutputStream(dest)
        val buf = ByteArray(65_536)
        var read: Int; var total = 0L
        val inp = body.byteStream()
        try {
            while (inp.read(buf).also { read = it } != -1) {
                fos.write(buf, 0, read); total += read
                if (totalBytes > 0) broadcastProgress((total * 100 / totalBytes).toInt(), dest.name)
            }
        } finally { fos.flush(); fos.close(); inp.close() }
        return dest
    }

    // ── Broadcasts ────────────────────────────────────────────────────────────

    private fun broadcastComplete(filename: String, savedPath: String, fromName: String) =
        sendBroadcast(Intent(ACTION_TRANSFER_COMPLETE).apply {
            putExtra("filename",  filename)
            putExtra("savedPath", savedPath)
            putExtra("fromName",  fromName)
        })

    internal fun broadcastProgress(pct: Int, filename: String) =
        sendBroadcast(Intent(ACTION_TRANSFER_PROGRESS).apply {
            putExtra("pct",      pct)
            putExtra("filename", filename)
        })

    private fun broadcastFailure(error: String) =
        sendBroadcast(Intent(ACTION_TRANSFER_FAILED).apply { putExtra("error", error) })

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); scope.cancel(); releaseWakeLock() }

    companion object {
        const val TAG                      = "BeamTransferService"
        const val NOTIFICATION_ID          = 1001
        const val ACTION_LOCAL_SEND_UPLOAD = "com.beam.app.LOCAL_SEND_UPLOAD"
        const val ACTION_START_DOWNLOAD    = "com.beam.app.START_DOWNLOAD"
        const val ACTION_TRANSFER_COMPLETE = "com.beam.app.TRANSFER_COMPLETE"
        const val ACTION_TRANSFER_PROGRESS = "com.beam.app.TRANSFER_PROGRESS"
        const val ACTION_TRANSFER_FAILED   = "com.beam.app.TRANSFER_FAILED"

        fun startUpload(context: Context, receiverIp: String, uris: List<Uri>, fromName: String) {
            val intent = Intent(context, BeamTransferService::class.java).apply {
                action = ACTION_LOCAL_SEND_UPLOAD
                putExtra("receiverIp",  receiverIp)
                putExtra("fromName",    fromName)
                putStringArrayListExtra("uriStrings", ArrayList(uris.map { it.toString() }))
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }
    }

    private fun jsonStr(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
