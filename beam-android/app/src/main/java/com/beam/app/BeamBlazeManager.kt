package com.beam.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.File

/**
 * Beam Blaze — device-to-device file transfer using Google Nearby Connections.
 *
 * Works without WiFi or internet. Uses whatever is available:
 * Bluetooth → WiFi Direct → WiFi LAN (auto-selected, fastest wins).
 *
 * Completely isolated from the rest of Beam. Only activated when the user
 * explicitly taps "Beam Blaze 🔥" in the Offline mode screen.
 *
 * Usage:
 *   val blaze = BeamBlazeManager(context, deviceName)
 *   blaze.start(callback)   → starts advertising + discovery simultaneously
 *   blaze.sendFile(endpointId, uri, filename)  → send a file
 *   blaze.acceptConnection(endpointId)         → accept incoming
 *   blaze.declineConnection(endpointId)        → decline incoming
 *   blaze.stop()            → clean up everything
 */
class BeamBlazeManager(
    private val context: Context,
    private val deviceName: String
) {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG        = "BeamBlaze"
        private const val SERVICE_ID = "com.beam.app.blaze"
    }

    // ── Callback interface (delivered to JavaScript via MainActivity) ─────────

    interface BlazeCallback {
        fun onDeviceFound(endpointId: String, name: String)
        fun onDeviceLost(endpointId: String)
        fun onIncomingConnection(endpointId: String, name: String)
        fun onConnected(endpointId: String, name: String)
        fun onDisconnected(endpointId: String)
        fun onTransferProgress(pct: Int, filename: String, bytes: Long, total: Long)
        fun onTransferComplete(filename: String)
        fun onTransferFailed(error: String)
        fun onError(message: String)
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private var callback: BlazeCallback?  = null
    var advertisingOnly: Boolean = false   // true = receive-only mode
    private val endpointNames = mutableMapOf<String, String>()  // endpointId → name
    private var activePayloadId: Long? = null
    private var activeFilename: String = ""
    private var activeFileTotal: Long  = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Full mode: advertise + discover (sender initiates) */
    fun start(cb: BlazeCallback) {
        callback        = cb
        advertisingOnly = false
        startAdvertising()
        startDiscovery()
    }

    /**
     * Receive-only mode: advertise only, no discovery.
     * Called silently when the user opens the Receive tab — makes this device
     * visible to Beam Blaze senders without any UI change for the receiver.
     */
    fun startReceiveMode(cb: BlazeCallback) {
        callback        = cb
        advertisingOnly = true
        startAdvertising()
        // No discovery — receiver just waits for incoming connections
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        callback      = null
        endpointNames.clear()
        activePayloadId = null
        Log.d(TAG, "Stopped")
    }

    // ── Advertising (makes this device visible to others) ─────────────────────

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()

        client.startAdvertising(deviceName, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener { Log.d(TAG, "Advertising started") }
            .addOnFailureListener { e ->
                Log.e(TAG, "Advertising failed: ${e.message}")
                callback?.onError("Could not start Beam Blaze: ${e.message}")
            }
    }

    // ── Discovery (scans for other Beam Blaze devices) ────────────────────────

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()

        client.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener { Log.d(TAG, "Discovery started") }
            .addOnFailureListener { e ->
                Log.e(TAG, "Discovery failed: ${e.message}")
                // Discovery failing is non-fatal — advertising still works
            }
    }

    // ── Connection management ─────────────────────────────────────────────────

    /** Sender taps a discovered device — initiates connection request */
    fun requestConnection(endpointId: String) {
        client.requestConnection(deviceName, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { e ->
                Log.e(TAG, "Connection request failed: ${e.message}")
                callback?.onError("Could not connect: ${e.message}")
            }
    }

    /** Called when user taps Accept on the incoming connection dialog */
    fun acceptConnection(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback)
    }

    /** Called when user taps Decline on the incoming connection dialog */
    fun declineConnection(endpointId: String) {
        client.rejectConnection(endpointId)
    }

    // ── File transfer ─────────────────────────────────────────────────────────

    /**
     * Send a file to a connected endpoint.
     * Uses Payload.fromUri() so the file is streamed directly — no loading
     * the whole file into memory, works with any file size.
     */
    fun sendFile(endpointId: String, fileUri: Uri, filename: String) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(fileUri, "r")
                ?: throw Exception("Cannot open file")
            val payload = Payload.fromFile(pfd)
            activePayloadId = payload.id
            activeFilename  = filename
            // Get file size for progress tracking
            context.contentResolver.query(fileUri,
                arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0) activeFileTotal = cursor.getLong(idx)
                }
            }
            client.sendPayload(endpointId, payload)
            Log.d(TAG, "Sending file: $filename to $endpointId")
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
            callback?.onTransferFailed("Could not send file: ${e.message}")
        }
    }

    // ── Discovery callback ─────────────────────────────────────────────────────

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val name = info.endpointName
            Log.d(TAG, "Found endpoint: $endpointId ($name)")
            endpointNames[endpointId] = name
            callback?.onDeviceFound(endpointId, name)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost endpoint: $endpointId")
            endpointNames.remove(endpointId)
            callback?.onDeviceLost(endpointId)
        }
    }

    // ── Connection lifecycle callback ──────────────────────────────────────────

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val name = info.endpointName
            Log.d(TAG, "Connection initiated from $endpointId ($name)")
            endpointNames[endpointId] = name
            // Notify JS — it will show the Accept/Decline popup
            callback?.onIncomingConnection(endpointId, name)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val name = endpointNames[endpointId] ?: "Unknown"
                    Log.d(TAG, "Connected to $endpointId ($name)")
                    callback?.onConnected(endpointId, name)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d(TAG, "Connection rejected by $endpointId")
                    callback?.onError("Connection declined by the other device")
                }
                else -> {
                    Log.e(TAG, "Connection failed: ${result.status}")
                    callback?.onError("Connection failed (${result.status.statusMessage})")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")
            endpointNames.remove(endpointId)
            callback?.onDisconnected(endpointId)
        }
    }

    // ── Payload (file transfer) callback ──────────────────────────────────────

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Log.d(TAG, "Receiving payload type=${payload.type} from $endpointId")
            // FILE payloads are automatically saved to a temp location by Nearby
            // We move the file to Downloads once transfer is complete (in onPayloadTransferUpdate)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val pct   = if (update.totalBytes > 0)
                (update.bytesTransferred * 100 / update.totalBytes).toInt() else 0
            val fname = if (update.payloadId == activePayloadId) activeFilename else ""

            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    callback?.onTransferProgress(pct, fname,
                        update.bytesTransferred, update.totalBytes)
                }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    Log.d(TAG, "Transfer complete: payload ${update.payloadId}")

                    // If we RECEIVED a file (not our activePayloadId), move it to Downloads
                    if (update.payloadId != activePayloadId) {
                        // Nearby saves received files to app's cache — move to Downloads
                        moveReceivedFileToDownloads(update.payloadId)
                    } else {
                        callback?.onTransferComplete(activeFilename)
                        activePayloadId = null
                        activeFilename  = ""
                    }
                }

                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    Log.e(TAG, "Transfer failed/cancelled for payload ${update.payloadId}")
                    callback?.onTransferFailed("Transfer was interrupted")
                    activePayloadId = null
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun moveReceivedFileToDownloads(payloadId: Long) {
        // Nearby saves files to filesDir/nearby_connections/ with payloadId as name
        val receivedFile = File(context.filesDir, "nearby_connections/$payloadId")
        if (!receivedFile.exists()) {
            // Try cache dir too
            val cacheFile = File(context.cacheDir, "$payloadId")
            if (cacheFile.exists()) saveToDownloads(cacheFile, payloadId.toString())
            return
        }
        saveToDownloads(receivedFile, payloadId.toString())
    }

    private fun saveToDownloads(src: File, fallbackName: String) {
        try {
            val downloadsDir = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            var dest = File(downloadsDir, src.name.takeIf { it.isNotEmpty() } ?: fallbackName)
            var i = 1
            while (dest.exists()) {
                val ext  = if (dest.name.contains('.')) ".${dest.name.substringAfterLast('.')}" else ""
                val base = if (dest.name.contains('.')) dest.name.substringBeforeLast('.') else dest.name
                dest = File(downloadsDir, "${base}_($i)$ext")
                i++
            }
            src.copyTo(dest, overwrite = false)
            src.delete()
            Log.d(TAG, "Saved to Downloads: ${dest.absolutePath}")
            callback?.onTransferComplete(dest.name)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move file to Downloads: ${e.message}")
            callback?.onTransferFailed("File saved but could not be moved to Downloads")
        }
    }
}
