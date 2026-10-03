package com.cross.app.notifications

import android.app.Notification
import android.app.Notification.Action
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.cross.app.CrossApplication
import com.cross.app.ble.MessageType
import com.cross.app.ble.ProtocolMessage
import org.json.JSONArray
import org.json.JSONObject

/** Captures allowed notifications and executes action/reply intents supplied by the posting app. */
class CrossNotificationListenerService : NotificationListenerService() {
    private val actions = mutableMapOf<String, List<Action>>()
    private val forwardedNotificationIDs = mutableSetOf<String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
        (application as? CrossApplication)?.runtime?.media?.refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val runtime = (application as? CrossApplication)?.runtime ?: return
        if (isMediaTransport(sbn)) runtime.media.refresh()
        if (!runtime.rules.isAllowed(sbn.packageName) || !isForwardable(sbn, runtime)) {
            actions.remove(sbn.key)
            removeForwardedNotification(sbn.key, runtime)
            return
        }
        forwardedNotificationIDs += sbn.key
        forwardNotification(sbn, runtime)
    }

    private fun isForwardable(sbn: StatusBarNotification, runtime: com.cross.app.CrossRuntime): Boolean {
        val notification = sbn.notification
        val extras = notification.extras
        return NotificationForwardingPolicy.isForwardable(
            NotificationMetadata(
                packageName = sbn.packageName,
                ownPackageName = runtime.appContext.packageName,
                isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                isMediaTransport = isMediaTransport(sbn),
                isOngoingService = notification.category == Notification.CATEGORY_SERVICE &&
                    notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
            ),
        )
    }

    private fun forwardNotification(sbn: StatusBarNotification, runtime: com.cross.app.CrossRuntime) {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val available = notification.actions?.toList().orEmpty()
        actions[sbn.key] = available
        val actionJson = JSONArray().apply {
            available.forEachIndexed { index, action ->
                put(JSONObject().put("id", index.toString()).put("title", action.title?.toString().orEmpty()).put("allows_reply", action.remoteInputs?.isNotEmpty() == true))
            }
        }
        val payload = ProtocolMessage.payload(
            "notification_id" to sbn.key,
            "app_name" to runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            }.getOrDefault(sbn.packageName),
            "title" to title,
            "body" to text,
            "received_at_ms" to sbn.postTime,
            "actions" to actionJson,
        )
        runtime.ble.send(ProtocolMessage(MessageType.NOTIFICATION, payloadJson = payload))
        Log.d(TAG, "Forwarded notification ${sbn.packageName} actions=${available.size}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        actions.remove(sbn.key)
        val runtime = (application as? CrossApplication)?.runtime
        if (isMediaTransport(sbn)) runtime?.media?.onNotificationRemoved(sbn.packageName)
        if (runtime != null) removeForwardedNotification(sbn.key, runtime)
    }

    private fun isMediaTransport(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        return notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
    }

    private fun removeForwardedNotification(notificationID: String, runtime: com.cross.app.CrossRuntime) {
        if (!forwardedNotificationIDs.remove(notificationID)) return
        runtime.ble.send(
            ProtocolMessage(
                MessageType.NOTIFICATION_REMOVED,
                payloadJson = ProtocolMessage.payload("notification_id" to notificationID),
            ),
        )
        Log.d(TAG, "Removed notification $notificationID from watch")
    }

    /** Sends active IDs and current notification payloads so the watch recovers missed notifications. */
    fun syncActiveNotificationIDs() {
        val runtime = (application as? CrossApplication)?.runtime ?: return
        val active = queryActiveNotifications() ?: return
        val forwardable = active.filter { runtime.rules.isAllowed(it.packageName) && isForwardable(it, runtime) }
        val activeIDs = forwardable.map { it.key }.toSet()
        forwardedNotificationIDs.retainAll(activeIDs)
        runtime.ble.send(
            ProtocolMessage(
                MessageType.NOTIFICATION_SYNC,
                payloadJson = ProtocolMessage.payload(
                    "notification_ids" to JSONArray().apply { activeIDs.forEach(::put) },
                ),
            ),
        )
        forwardable.forEach { notification ->
            forwardedNotificationIDs += notification.key
            forwardNotification(notification, runtime)
        }
        Log.d(TAG, "Synchronized active notifications count=${forwardable.size}")
    }

    fun performAction(payloadJson: String): Boolean {
        val payload = JSONObject(payloadJson)
        val eventId = payload.optString("notification_id", payload.optString("eventId"))
        val index = payload.optString("action_id").toIntOrNull() ?: payload.optInt("index", -1)
        val runtime = (application as? CrossApplication)?.runtime ?: return false
        val active = queryActiveNotifications()?.firstOrNull { notification ->
            notification.key == eventId && runtime.rules.isAllowed(notification.packageName) && isForwardable(notification, runtime)
        } ?: run {
            Log.w(TAG, "Ignoring action for inactive or non-forwardable notification $eventId")
            return false
        }
        val available = actions[eventId] ?: active.notification.actions?.toList().orEmpty().also {
            actions[eventId] = it
            Log.d(TAG, "Rebuilt actions for active notification $eventId count=${it.size}")
        }
        val action = available.getOrNull(index) ?: return false
        val reply = payload.optString("reply", "")
        return runCatching {
            val intent = Intent()
            action.remoteInputs?.forEach { remoteInput -> RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, android.os.Bundle().apply { putCharSequence(remoteInput.resultKey, reply) }) }
            action.actionIntent.send(this, 0, intent)
            true
        }.getOrElse {
            Log.w(TAG, "Notification action failed", it)
            false
        }
    }

    private fun queryActiveNotifications(): List<StatusBarNotification>? = runCatching {
        activeNotifications.orEmpty().toList()
    }.onFailure {
        Log.w(TAG, "Unable to read active notifications", it)
    }.getOrNull()

    companion object {
        private const val TAG = "CrossNotifications"
        @Volatile private var current: CrossNotificationListenerService? = null

        fun performAction(payloadJson: String): Boolean = current?.performAction(payloadJson) ?: false

        fun syncActiveNotificationIDs() { current?.syncActiveNotificationIDs() }
    }

    override fun onCreate() { super.onCreate(); current = this }
    override fun onDestroy() { current = null; super.onDestroy() }
}
