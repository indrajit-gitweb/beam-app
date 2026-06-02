package com.beam.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object BeamNotificationHelper {

    const val CHANNEL_ID        = "beam_transfer"
    const val NOTIF_TRANSFER_ID = 1001
    const val NOTIF_SIMPLE_ID   = 1002

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress for Beam file transfers"
                setShowBadge(false)
            }
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(channel)
        }
    }

    fun buildProgress(context: Context, title: String, progress: Int): Notification {
        createChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (progress > 0) "$progress%" else "Starting…")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateProgress(context: Context, title: String, progress: Int) {
        val notif = buildProgress(context, title, progress)
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_TRANSFER_ID, notif)
        } catch (e: SecurityException) { /* notification permission not granted */ }
    }

    fun showComplete(context: Context, title: String, body: String) {
        createChannel(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).apply {
                cancel(NOTIF_TRANSFER_ID)
                notify(NOTIF_SIMPLE_ID, notif)
            }
        } catch (e: SecurityException) {}
    }

    fun showError(context: Context, title: String, body: String) {
        createChannel(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).apply {
                cancel(NOTIF_TRANSFER_ID)
                notify(NOTIF_SIMPLE_ID, notif)
            }
        } catch (e: SecurityException) {}
    }

    fun showSimple(context: Context, title: String, body: String) {
        createChannel(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_SIMPLE_ID, notif)
        } catch (e: SecurityException) {}
    }
}
