package com.cross.app

import android.app.Application
import android.util.Log
import com.cross.app.ble.CrossBleForegroundService
import com.cross.app.ble.CrossBleServer
import com.cross.app.ble.InstallIdentityCodec
import com.cross.app.health.HealthRepository
import com.cross.app.health.HealthConnectRepository
import com.cross.app.health.HealthBatchAcceptance
import com.cross.app.health.HealthSyncJson
import com.cross.app.health.HealthSyncOutcome
import com.cross.app.health.HealthSyncFailureMessages
import com.cross.app.media.MediaManager
import com.cross.app.notifications.NotificationRules
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject

/** Application composition root. Background services use the same private runtime instance as the UI. */
class CrossApplication : Application() {
    lateinit var runtime: CrossRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = CrossRuntime(this)
    }
}

/** Process-local single-user services; no account, cloud, or multi-device identity is introduced. */
class CrossRuntime(context: Application) {
    val appContext: Application = context
    private val connectionPreferences = context.getSharedPreferences("cross_connection", android.content.Context.MODE_PRIVATE)
    private val deviceId = loadOrCreateDeviceId()
    private val healthSyncMutex = Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val rules = NotificationRules(context)
    val health = HealthRepository(context)
    val healthConnect = HealthConnectRepository(context)
    val media = MediaManager(context)
    private val deviceLocator = DeviceLocator(context)
    val ble = CrossBleServer(context).also { transport ->
        transport.onMessage = { message, _ ->
            when (message.type) {
                com.cross.app.ble.MessageType.HELLO -> {
                    val capabilities = JSONArray()
                        .put("notifications")
                        .put("media")
                        .put("volume")
                        .put("health")
                        .put("find_device")
                    val queued = transport.send(com.cross.app.ble.ProtocolMessage(com.cross.app.ble.MessageType.HELLO, payloadJson = com.cross.app.ble.ProtocolMessage.payload("device_id" to deviceId, "device_name" to "Cross Android", "app_version" to BuildConfig.VERSION_NAME, "capabilities" to capabilities)))
                    if (queued) {
                        scope.launch {
                            yield()
                            media.refresh()
                        }
                    }
                    queued
                }
                com.cross.app.ble.MessageType.MEDIA_COMMAND -> media.handle(message)
                com.cross.app.ble.MessageType.NOTIFICATION_ACTION -> com.cross.app.notifications.CrossNotificationListenerService.performAction(message.payloadJson)
                com.cross.app.ble.MessageType.NOTIFICATION_SYNC_REQUEST -> {
                    com.cross.app.notifications.CrossNotificationListenerService.syncActiveNotificationIDs()
                    true
                }
                com.cross.app.ble.MessageType.HEALTH_SYNC -> {
                    handleHealthSync(message, transport)
                    true
                }
                com.cross.app.ble.MessageType.FIND_DEVICE -> handleFindDevice(message.payloadJson)
                com.cross.app.ble.MessageType.PING -> true
                else -> false
            }
        }
    }

    init {
        media.onStateChanged = { state ->
            ble.send(
                com.cross.app.ble.ProtocolMessage(
                    com.cross.app.ble.MessageType.MEDIA_STATE,
                    payloadJson = com.cross.app.ble.ProtocolMessage.payload(
                        "title" to state.title,
                        "artist" to state.artist,
                        "album" to state.album,
                        "is_playing" to state.isPlaying,
                        "position_ms" to state.positionMs,
                        "duration_ms" to state.durationMs,
                    ),
                ),
            )
        }
    }

    private fun handleHealthSync(message: com.cross.app.ble.ProtocolMessage, transport: CrossBleServer) {
        val batch = try {
            HealthSyncJson.decode(message.payloadJson)
        } catch (error: Exception) {
            Log.e(TAG, "Health payload rejected: ${error.javaClass.simpleName}")
            healthConnect.reportInvalidPayloadFailure()
            transport.send(com.cross.app.ble.ProtocolMessage.ack(message.id, success = false, error = HealthSyncFailureMessages.INVALID_PAYLOAD))
            return
        }
        scope.launch {
            val ack = withContext(Dispatchers.IO) {
                healthSyncMutex.withLock { importHealthSync(message.id, batch) }
            }
            transport.send(ack)
        }
    }

    private suspend fun importHealthSync(
        requestId: String,
        batch: com.cross.app.health.HealthSyncBatch,
    ): com.cross.app.ble.ProtocolMessage = try {
        when (health.accept(requestId, batch)) {
            HealthBatchAcceptance.ALREADY_IMPORTED -> com.cross.app.ble.ProtocolMessage.ack(requestId, success = true)
            HealthBatchAcceptance.NEEDS_IMPORT -> when (healthConnect.sync(batch)) {
                HealthSyncOutcome.Completed -> {
                    health.markSynced(batch)
                    com.cross.app.ble.ProtocolMessage.ack(requestId, success = true)
                }
                HealthSyncOutcome.Unavailable -> com.cross.app.ble.ProtocolMessage.ack(
                    requestId,
                    success = false,
                    error = "Health Connect is unavailable; the generation remains pending",
                )
                HealthSyncOutcome.PermissionRequired -> com.cross.app.ble.ProtocolMessage.ack(
                    requestId,
                    success = false,
                    error = "Health Connect permissions are required; the generation remains pending",
                )
            }
        }
    } catch (error: Exception) {
        Log.e(TAG, "Health Connect import failed: ${error.javaClass.simpleName}")
        healthConnect.reportImportFailure()
        com.cross.app.ble.ProtocolMessage.ack(requestId, success = false, error = HealthSyncFailureMessages.IMPORT_FAILED)
    }

    private fun handleFindDevice(payloadJson: String): Boolean {
        if (JSONObject(payloadJson).optString("target") != "phone") return false
        return deviceLocator.ringPhone()
    }

    fun syncPendingHealthToHealthConnect() {
        scope.launch(Dispatchers.IO) {
            healthSyncMutex.withLock {
                val batch = health.latest.value
                if (batch == null || !health.hasPendingSync) {
                    healthConnect.refreshStatus(batch)
                    return@withLock
                }
                try {
                    if (healthConnect.sync(batch) == HealthSyncOutcome.Completed) {
                        health.markSynced(batch)
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Health Connect sync failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    fun startBleService() {
        connectionPreferences.edit().putBoolean(KEY_BLE_ENABLED, true).apply()
        ContextCompat.startForegroundService(
            appContext,
            CrossBleForegroundService.intent(appContext, CrossBleForegroundService.ACTION_START),
        )
    }

    fun stopBleService() {
        connectionPreferences.edit().putBoolean(KEY_BLE_ENABLED, false).apply()
        ble.stop()
        appContext.stopService(CrossBleForegroundService.intent(appContext, CrossBleForegroundService.ACTION_STOP))
    }

    fun shouldStartBleService(): Boolean = connectionPreferences.getBoolean(KEY_BLE_ENABLED, false)

    fun replacePairedWatch(): Result<Unit> {
        val resetResult = ble.resetPeerBinding()
        if (resetResult.isFailure) {
            val resetError = checkNotNull(resetResult.exceptionOrNull()) { "Failed peer reset did not contain an error" }
            return Result.failure(
                IllegalStateException("Could not clear the paired watch binding", resetError),
            )
        }
        return runCatching {
            startBleService()
            ble.start().getOrThrow()
        }.recoverCatching { error ->
            throw IllegalStateException("The paired watch was cleared, but Bluetooth advertising could not start", error)
        }
    }

    fun stopPhoneRing() = deviceLocator.stopRingPhone()

    private fun loadOrCreateDeviceId(): String {
        InstallIdentityCodec.decode(connectionPreferences.all, KEY_DEVICE_ID_INITIALIZED, KEY_DEVICE_ID)?.let { return it }
        val generated = InstallIdentityCodec.generate()
        check(connectionPreferences.edit()
            .putBoolean(KEY_DEVICE_ID_INITIALIZED, true)
            .putString(KEY_DEVICE_ID, generated)
            .commit()) { "Could not persist the Android device identity" }
        return checkNotNull(
            InstallIdentityCodec.decode(connectionPreferences.all, KEY_DEVICE_ID_INITIALIZED, KEY_DEVICE_ID),
        ) { "Persisted Android device identity is missing" }
    }

    private companion object {
        const val KEY_BLE_ENABLED = "ble_enabled"
        const val KEY_DEVICE_ID_INITIALIZED = "android_device_id_initialized"
        const val KEY_DEVICE_ID = "android_device_id"
        const val TAG = "CrossHealthSync"
    }
}
