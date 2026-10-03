package com.cross.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import org.json.JSONException

internal class BoundedNotificationQueue(
    private val maxChunks: Int,
    private val maxBytes: Int,
) {
    private val chunks = ArrayDeque<ByteArray>()
    private var byteCount = 0

    init {
        require(maxChunks > 0) { "maxChunks must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    @Synchronized
    fun enqueueFrame(frameChunks: List<ByteArray>): Boolean {
        val frameBytes = frameChunks.sumOf { it.size.toLong() }
        if (frameChunks.isEmpty() || frameChunks.size > maxChunks - chunks.size || frameBytes > maxBytes - byteCount.toLong()) {
            return false
        }
        frameChunks.forEach { chunk ->
            chunks.addLast(chunk)
            byteCount += chunk.size
        }
        return true
    }

    @Synchronized
    fun firstOrNull(): ByteArray? = chunks.firstOrNull()

    @Synchronized
    fun completeFirst() {
        val completed = chunks.removeFirst()
        byteCount -= completed.size
    }

    @Synchronized
    fun clear() {
        chunks.clear()
        byteCount = 0
    }

    @Synchronized
    fun size(): Int = chunks.size

    @Synchronized
    fun bytes(): Int = byteCount
}

internal data class PeerBinding(val address: String, val deviceId: String)

internal object PeerBindingCodec {
    private val addressPattern = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    fun decode(storedValues: Map<String, *>, addressKey: String, deviceIdKey: String): PeerBinding? {
        val hasAddress = storedValues.containsKey(addressKey)
        val hasDeviceId = storedValues.containsKey(deviceIdKey)
        if (!hasAddress && !hasDeviceId) return null
        check(hasAddress && hasDeviceId) { "Stored BLE peer binding is incomplete" }
        val address = storedValues[addressKey] as? String ?: error("Stored BLE peer address has the wrong type")
        val deviceId = storedValues[deviceIdKey] as? String ?: error("Stored BLE peer device_id has the wrong type")
        return encode(address, deviceId)
    }

    fun encode(address: String, deviceId: String): PeerBinding = PeerBinding(
        address = normalizeAddress(address),
        deviceId = normalizeDeviceId(deviceId),
    )

    fun normalizeAddress(address: String): String = requireNotNull(address.takeIf(addressPattern::matches)) {
        "Bluetooth peer address is invalid"
    }.uppercase(Locale.ROOT)

    fun normalizeDeviceId(deviceId: String): String {
        return requireBoundedProtocolIdentifier(deviceId, "device_id")
    }
}

internal object InstallIdentityCodec {
    fun decode(storedValues: Map<String, *>, initializedKey: String, deviceIdKey: String): String? {
        val hasInitialized = storedValues.containsKey(initializedKey)
        val hasDeviceId = storedValues.containsKey(deviceIdKey)
        if (!hasInitialized && !hasDeviceId) return null
        check(hasInitialized && hasDeviceId) { "Stored Android device identity is incomplete" }
        check(storedValues[initializedKey] == true) { "Stored Android device identity marker is invalid" }
        val storedDeviceId = storedValues[deviceIdKey] as? String
            ?: error("Stored Android device_id has the wrong type")
        val parsed = runCatching { UUID.fromString(storedDeviceId) }
            .getOrElse { throw IllegalStateException("Stored Android device_id is corrupt", it) }
        check(parsed.toString() == storedDeviceId) { "Stored Android device_id is not a canonical UUID" }
        return requireBoundedProtocolIdentifier(storedDeviceId, "device_id")
    }

    fun generate(): String = UUID.randomUUID().toString()
}

internal data class PeerBindingState(
    val binding: PeerBinding? = null,
    val helloValidated: Boolean = false,
) {
    fun beginConnection(): PeerBindingState = copy(helloValidated = false)

    fun acceptHello(candidate: PeerBinding): PeerBindingState {
        require(binding == null || binding == candidate) { "BLE HELLO does not match the durable peer binding" }
        return PeerBindingState(binding = binding ?: candidate, helloValidated = true)
    }

    fun reset(): PeerBindingState = PeerBindingState()
}

internal object PeerSessionPolicy {
    fun acceptsConnection(hasBinding: Boolean, isBonded: Boolean, matchesBinding: Boolean): Boolean =
        !hasBinding || isBonded && matchesBinding

    fun acceptsAuthenticatedInbound(hasBinding: Boolean, isBonded: Boolean, matchesBinding: Boolean): Boolean =
        isBonded && (!hasBinding || matchesBinding)

    fun allowsSubscription(hasBinding: Boolean, isBonded: Boolean, matchesBinding: Boolean): Boolean =
        acceptsAuthenticatedInbound(hasBinding, isBonded, matchesBinding)

    fun allowsBindingClaim(isBonded: Boolean, notificationsEnabled: Boolean, isValidHello: Boolean): Boolean =
        isBonded && notificationsEnabled && isValidHello

    fun allowsApplicationData(
        hasBinding: Boolean,
        isBonded: Boolean,
        matchesBinding: Boolean,
        notificationsEnabled: Boolean,
        helloValidated: Boolean,
    ): Boolean = hasBinding && isBonded && matchesBinding && notificationsEnabled && helloValidated
}

/** Phone-side GATT server. The Apple Watch acts as the central and writes RX/read-notifies TX. */
class CrossBleServer(private val context: Context) {
    enum class Status { STOPPED, STARTING, ADVERTISING, CONNECTED, ERROR }
    data class Connection(val status: Status, val deviceName: String? = null, val address: String? = null, val detail: String? = null)

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7f4f0001-9b6e-4c5b-8b4e-4a7b8b90c001")
        val RX_UUID: UUID = UUID.fromString("7f4f0002-9b6e-4c5b-8b4e-4a7b8b90c001")
        val TX_UUID: UUID = UUID.fromString("7f4f0003-9b6e-4c5b-8b4e-4a7b8b90c001")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TAG = "CrossBleServer"
        private const val CONNECTION_PREFERENCES = "cross_connection"
        private const val BOUND_PEER_ADDRESS_KEY = "bound_ble_peer_address"
        private const val BOUND_PEER_DEVICE_ID_KEY = "bound_ble_peer_device_id"
        private const val MAX_PENDING_NOTIFICATION_CHUNKS = 2_048
        private const val MAX_PENDING_NOTIFICATION_BYTES = 256 * 1024
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val connectionPreferences = context.getSharedPreferences(CONNECTION_PREFERENCES, Context.MODE_PRIVATE)
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connectedDevice: BluetoothDevice? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var notificationsEnabled = false
    private var notificationInFlight = false
    private var peerBindingState = PeerBindingState()
    private val pendingNotifications = BoundedNotificationQueue(MAX_PENDING_NOTIFICATION_CHUNKS, MAX_PENDING_NOTIFICATION_BYTES)
    private val reassembler = FrameCodec.Reassembler()
    private val _connection = MutableStateFlow(Connection(Status.STOPPED))
    val connection: StateFlow<Connection> = _connection.asStateFlow()
    var onMessage: ((ProtocolMessage, BluetoothDevice) -> Boolean)? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Result<Unit> = runCatching {
        if (!hasBluetoothPermissions()) error("Bluetooth permissions are not granted")
        val adapter = bluetoothManager?.adapter ?: error("Bluetooth is not available")
        if (!adapter.isEnabled) error("Bluetooth is turned off")
        if (server != null) return@runCatching Unit
        _connection.value = Connection(Status.STARTING)
        peerBindingState = PeerBindingState(
            binding = PeerBindingCodec.decode(connectionPreferences.all, BOUND_PEER_ADDRESS_KEY, BOUND_PEER_DEVICE_ID_KEY),
        )
        server = bluetoothManager.openGattServer(context, callback)
            ?: error("Could not open the GATT server")
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(RX_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM)
        tx = BluetoothGattCharacteristic(TX_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM)
        tx?.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED_MITM or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED_MITM))
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        check(server?.addService(service) == true) { "Could not add Cross GATT service" }
        advertiser = adapter.bluetoothLeAdvertiser ?: error("BLE advertising is not supported")
        advertiser?.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),
            // Keep the primary packet within the legacy 31-byte limit. The watch scans by UUID, so the
            // system device name is not needed for discovery and can make Samsung advertising fail with code 1.
            AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            advertiseCallback,
        )
        Log.i(TAG, "GATT server started; advertising ${SERVICE_UUID}")
        _connection.value = Connection(Status.ADVERTISING, detail = "Waiting for Apple Watch")
    }.onFailure {
        Log.e(TAG, "Unable to start GATT server", it)
        cleanupTransport()
        _connection.value = Connection(Status.ERROR, detail = it.message)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop() {
        cleanupTransport()
        peerBindingState = PeerBindingState()
        _connection.value = Connection(Status.STOPPED)
        Log.i(TAG, "GATT server stopped")
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun cleanupTransport() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        server?.close()
        server = null
        tx = null
        connectedDevice = null
        notificationsEnabled = false
        notificationInFlight = false
        pendingNotifications.clear()
        reassembler.reset()
        peerBindingState = peerBindingState.beginConnection()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun send(message: ProtocolMessage): Boolean {
        val device = connectedDevice ?: return false
        if (tx == null || server == null) return false
        val hasBinding = peerBindingState.binding != null
        if (!PeerSessionPolicy.allowsApplicationData(
                hasBinding,
                isBonded(device),
                hasBinding && matchesBinding(device),
                notificationsEnabled,
                peerBindingState.helloValidated,
            )
        ) {
            if (hasBinding && (!isBonded(device) || !matchesBinding(device))) abortConnection(device, "BLE peer authentication was lost")
            return false
        }
        if (!pendingNotifications.enqueueFrame(FrameCodec.encode(message))) return false
        sendNextNotification(device)
        return true
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun sendNextNotification(device: BluetoothDevice? = connectedDevice) {
        val targetDevice = device ?: return
        if (!notificationsEnabled || notificationInFlight) return
        val characteristic = tx ?: return
        val gatt = server ?: return
        val nextChunk = pendingNotifications.firstOrNull() ?: return

        characteristic.value = nextChunk
        notificationInFlight = true
        if (!gatt.notifyCharacteristicChanged(targetDevice, characteristic, false)) {
            Log.w(TAG, "BLE notification could not be queued")
            abortConnection(targetDevice, "BLE notification delivery failed")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun abortConnection(device: BluetoothDevice, detail: String) {
        if (isConnectedDevice(device)) {
            connectedDevice = null
            notificationsEnabled = false
            notificationInFlight = false
            pendingNotifications.clear()
            reassembler.reset()
            peerBindingState = peerBindingState.beginConnection()
            _connection.value = Connection(Status.ERROR, detail = detail)
        }
        server?.cancelConnection(device)
    }

    private fun isConnectedDevice(device: BluetoothDevice): Boolean = connectedDevice?.address == device.address

    private fun isBonded(device: BluetoothDevice): Boolean = device.bondState == BluetoothDevice.BOND_BONDED

    private fun accessFailureStatus(device: BluetoothDevice): Int =
        if (isBonded(device)) BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION else BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION

    private fun matchesBinding(device: BluetoothDevice): Boolean =
        peerBindingState.binding?.address == PeerBindingCodec.normalizeAddress(device.address)

    @Synchronized
    private fun acceptHello(device: BluetoothDevice, deviceId: String): Boolean {
        val candidate = PeerBindingCodec.encode(device.address, deviceId)
        val nextState = peerBindingState.acceptHello(candidate)
        if (peerBindingState.binding == null) {
            val persisted = connectionPreferences.edit()
                .putString(BOUND_PEER_ADDRESS_KEY, candidate.address)
                .putString(BOUND_PEER_DEVICE_ID_KEY, candidate.deviceId)
                .commit()
            if (!persisted) return false
        }
        peerBindingState = nextState
        _connection.value = Connection(Status.CONNECTED)
        return true
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun resetPeerBinding(): Result<Unit> = runCatching {
        check(
            connectionPreferences.edit()
                .remove(BOUND_PEER_ADDRESS_KEY)
                .remove(BOUND_PEER_DEVICE_ID_KEY)
                .commit(),
        ) { "Could not durably clear BLE peer binding" }
        val device = connectedDevice
        connectedDevice = null
        notificationsEnabled = false
        notificationInFlight = false
        pendingNotifications.clear()
        reassembler.reset()
        peerBindingState = peerBindingState.reset()
        if (device != null) server?.cancelConnection(device)
        _connection.value = if (server == null) {
            Connection(Status.STOPPED)
        } else {
            Connection(Status.ADVERTISING, detail = "Peer binding reset")
        }
    }

    private fun hasBluetoothPermissions(): Boolean = android.os.Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            synchronized(this@CrossBleServer) {
                val detail = advertiseFailureMessage(errorCode)
                Log.e(TAG, "BLE advertising failed: $detail")
                cleanupTransport()
                _connection.value = Connection(Status.ERROR, detail = detail)
            }
        }
    }

    private fun advertiseFailureMessage(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "BLE advertising data is too large (error 1) even with the compact service-only payload"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Bluetooth has too many active advertisers; stop another BLE app and retry"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "BLE advertising is already active"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Android reported an internal BLE advertising error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "This phone does not support BLE advertising"
        else -> "BLE advertising failed (error $errorCode)"
    }

    private val callback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            synchronized(this@CrossBleServer) {
                Log.i(TAG, "GATT connection state changed: state=$newState status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val hasBinding = peerBindingState.binding != null
                    if (!PeerSessionPolicy.acceptsConnection(hasBinding, isBonded(device), hasBinding && matchesBinding(device))) {
                        server?.cancelConnection(device)
                        _connection.value = Connection(Status.ERROR, detail = "Rejected a BLE peer that does not match the durable binding")
                        return@synchronized
                    }
                    if (connectedDevice != null && !isConnectedDevice(device)) {
                        server?.cancelConnection(device)
                        return@synchronized
                    }
                    connectedDevice = device
                    reassembler.reset()
                    notificationsEnabled = false
                    notificationInFlight = false
                    pendingNotifications.clear()
                    peerBindingState = peerBindingState.beginConnection()
                    _connection.value = Connection(Status.STARTING, detail = "Waiting for peer HELLO")
                } else if (isConnectedDevice(device)) {
                    connectedDevice = null
                    reassembler.reset()
                    notificationsEnabled = false
                    notificationInFlight = false
                    pendingNotifications.clear()
                    peerBindingState = peerBindingState.beginConnection()
                    _connection.value = Connection(Status.ADVERTISING, detail = "Watch disconnected; reconnecting")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(this@CrossBleServer) {
                if (!isConnectedDevice(device) || !notificationInFlight) return@synchronized
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "BLE notification failed with status=$status")
                    abortConnection(device, "BLE notification delivery failed")
                    return@synchronized
                }
                notificationInFlight = false
                pendingNotifications.completeFirst()
                sendNextNotification(device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            synchronized(this@CrossBleServer) {
                val hasBinding = peerBindingState.binding != null
                if (!isConnectedDevice(device) || !PeerSessionPolicy.acceptsAuthenticatedInbound(hasBinding, isBonded(device), hasBinding && matchesBinding(device))) {
                    if (responseNeeded) server?.sendResponse(device, requestId, accessFailureStatus(device), 0, null)
                    abortConnection(device, "Rejected an unauthenticated BLE write")
                    return@synchronized
                }
                if (characteristic.uuid != RX_UUID || preparedWrite) {
                    if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                    return@synchronized
                }
                if (offset != 0) {
                    if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, 0, null)
                    return@synchronized
                }
                val complete = reassembler.accept(value)
                if (complete == null) {
                    if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    return@synchronized
                }
                var helloDeviceId: String? = null
                val message = try {
                    ProtocolMessage.fromJson(complete.toString(StandardCharsets.UTF_8)).also { parsed ->
                        if (parsed.type == MessageType.HELLO) {
                            require(PeerSessionPolicy.allowsBindingClaim(isBonded(device), notificationsEnabled, isValidHello = true)) {
                                "A BLE peer must subscribe before sending HELLO"
                            }
                            helloDeviceId = validateHelloMessage(parsed)
                        } else {
                            require(
                                PeerSessionPolicy.allowsApplicationData(
                                    hasBinding,
                                    isBonded(device),
                                    hasBinding && matchesBinding(device),
                                    notificationsEnabled,
                                    peerBindingState.helloValidated,
                                ),
                            ) { "A BLE peer must subscribe and complete HELLO before application messages" }
                        }
                    }
                } catch (_: JSONException) {
                    rejectProtocolMessage(device, requestId, responseNeeded)
                    return@synchronized
                } catch (_: IllegalArgumentException) {
                    rejectProtocolMessage(device, requestId, responseNeeded)
                    return@synchronized
                } catch (_: IllegalStateException) {
                    rejectProtocolMessage(device, requestId, responseNeeded)
                    return@synchronized
                }
                if (helloDeviceId != null) {
                    val helloAccepted = try {
                        acceptHello(device, helloDeviceId!!)
                    } catch (_: IllegalArgumentException) {
                        rejectProtocolMessage(device, requestId, responseNeeded)
                        return@synchronized
                    }
                    if (!helloAccepted) {
                        Log.e(TAG, "BLE peer binding could not be persisted")
                        if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        abortConnection(device, "Could not persist BLE peer binding")
                        return@synchronized
                    }
                }
                Log.d(TAG, "Received ${message.type.wireName} message")
                val handledSuccessfully = onMessage?.invoke(message, device) ?: false
                if (ProtocolMessage.shouldAutomaticallyAck(message)) {
                    send(ProtocolMessage.ack(message.id, success = handledSuccessfully))
                }
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            synchronized(this@CrossBleServer) {
                val hasBinding = peerBindingState.binding != null
                if (!isConnectedDevice(device) || !PeerSessionPolicy.allowsSubscription(hasBinding, isBonded(device), hasBinding && matchesBinding(device))) {
                    if (responseNeeded) server?.sendResponse(device, requestId, accessFailureStatus(device), 0, null)
                    abortConnection(device, "Rejected an unauthenticated CCCD subscription")
                    return@synchronized
                }
                val validRequest = descriptor.uuid == CCCD_UUID && !preparedWrite && offset == 0
                if (!validRequest) {
                    val responseStatus = if (offset != 0) BluetoothGatt.GATT_INVALID_OFFSET else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    if (responseNeeded) server?.sendResponse(device, requestId, responseStatus, 0, null)
                    return@synchronized
                }
                when {
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                        notificationsEnabled = true
                        if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        sendNextNotification(device)
                    }
                    value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                        notificationsEnabled = false
                        if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                    else -> if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun rejectProtocolMessage(device: BluetoothDevice, requestId: Int, responseNeeded: Boolean) {
        Log.w(TAG, "Rejected invalid BLE protocol message")
        if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
        abortConnection(device, "Rejected invalid BLE protocol message")
    }
}
