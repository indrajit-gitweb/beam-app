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

    // ── Received payload registry ─────────────────────────────────────────────
    // Store incoming Payload objects by ID so we can call .asFile().asJavaFile()
    // in onPayloadTransferUpdate — the official API, no path guessing needed.
    private val receivedPayloads = java.util.concurrent.ConcurrentHashMap<Long, Payload>()

    // ── Payload (file transfer) callback ──────────────────────────────────────

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Log.d(TAG, "Receiving payload id=${payload.id} type=${payload.type} from $endpointId")
            // Store FILE payloads so we can retrieve the exact temp path on completion
            if (payload.type == Payload.Type.FILE) {
                receivedPayloads[payload.id] = payload
            }
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

                    if (update.payloadId != activePayloadId) {
                        // We RECEIVED this file — save it to Downloads
                        val payload = receivedPayloads.remove(update.payloadId)
                        saveReceivedPayload(payload, update.payloadId)
                    } else {
                        // We SENT this file — notify sender side
                        callback?.onTransferComplete(activeFilename)
                        activePayloadId = null
                        activeFilename  = ""
                    }
                }

                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    Log.e(TAG, "Transfer failed/cancelled for payload ${update.payloadId}")
                    receivedPayloads.remove(update.payloadId)
                    callback?.onTransferFailed("Transfer was interrupted")
                    activePayloadId = null
                }
            }
        }
    }

    // ── File saving ───────────────────────────────────────────────────────────

    /**
     * Save a received FILE payload to the Downloads folder.
     *
     * Uses payload.asFile().asJavaFile() — the official Nearby Connections API —
     * so we always get the exact temp file path regardless of which Google Play
     * Services version is installed. No path guessing, no silent data loss.
     *
     * Error handling:
     *   - null payload  → logged + user notified (should never happen in practice)
     *   - wrong type    → logged + user notified
     *   - no temp file  → logged + user notified
     *   - storage full  → catches IOException, notifies user to free space
     *   - permission    → catches SecurityException, notifies user
     */
    private fun saveReceivedPayload(payload: Payload?, payloadId: Long) {
        if (payload == null) {
            Log.e(TAG, "Payload $payloadId missing from registry — file lost")
            callback?.onTransferFailed("Received file could not be located. Please try again.")
            return
        }

        if (payload.type != Payload.Type.FILE) {
            Log.e(TAG, "Payload $payloadId is not a FILE type (${payload.type}) — cannot save")
            callback?.onTransferFailed("Unsupported transfer type. Only files are supported.")
            return
        }

        val tempFile = payload.asFile()?.asJavaFile()
        if (tempFile == null || !tempFile.exists()) {
            Log.e(TAG, "Temp file for payload $payloadId not found at ${tempFile?.absolutePath}")
            callback?.onTransferFailed(
                "Received file could not be found after transfer. " +
                "Storage may be full or permissions may be missing."
            )
            return
        }

        val downloadsDir = android.os.Environment
            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)

        // Check available space before attempting the copy
        val requiredBytes = tempFile.length()
        val availableBytes = downloadsDir.freeSpace
        if (availableBytes < requiredBytes + 1_048_576L) { // keep 1 MB buffer
            Log.e(TAG, "Not enough space: need ${requiredBytes}B, have ${availableBytes}B")
            tempFile.delete()
            callback?.onTransferFailed(
                "Not enough storage space to save the file. " +
                "Free up space and ask the sender to try again."
            )
            return
        }

        // Use the original filename from the temp file (Nearby preserves it)
        // Fall back to payloadId if name is empty for any reason
        val originalName = tempFile.name.takeIf { it.isNotEmpty() } ?: "beam_blaze_$payloadId"

        // Avoid overwriting existing files — append (1), (2), etc.
        var dest = File(downloadsDir, originalName)
        var counter = 1
        while (dest.exists()) {
            val ext  = if (originalName.contains('.')) ".${originalName.substringAfterLast('.')}" else ""
            val base = if (originalName.contains('.')) originalName.substringBeforeLast('.') else originalName
            dest = File(downloadsDir, "${base}_($counter)$ext")
            counter++
        }

        try {
            tempFile.copyTo(dest, overwrite = false)
            tempFile.delete()   // clean up temp file after successful copy
            Log.d(TAG, "Saved to Downloads: ${dest.absolutePath} (${dest.length()} B)")
            callback?.onTransferComplete(dest.name)
        } catch (e: java.io.IOException) {
            Log.e(TAG, "IO error saving to Downloads: ${e.message}")
            dest.delete()   // remove partial file if copy failed mid-way
            callback?.onTransferFailed(
                "Could not save the file — storage may be full or unavailable. " +
                "Error: ${e.message}"
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error saving to Downloads: ${e.message}")
            callback?.onTransferFailed(
                "Storage permission denied. Please allow Beam to access Downloads " +
                "in device Settings and try again."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error saving to Downloads: ${e.message}")
            callback?.onTransferFailed("Unexpected error saving file: ${e.message}")
        }
    }
}
