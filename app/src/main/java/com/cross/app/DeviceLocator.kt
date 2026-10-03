package com.cross.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cross.app.media.logicalActionSucceeded

/** Local find-device hooks. Phone-side audio is controlled by the watch and stopped from its alert. */
class DeviceLocator(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable { stopRingPhone() }
    private var ringtone: android.media.Ringtone? = null

    @Synchronized
    fun ringPhone(): Boolean {
        stopRingPhone()
        val soundStarted = logicalActionSucceeded(
            action = {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val next = RingtoneManager.getRingtone(context, uri) ?: error("No phone ringtone is available")
                if (Build.VERSION.SDK_INT >= 28) next.isLooping = true
                ringtone = next
                next.play()
            },
            onFailure = {
                ringtone?.stop()
                ringtone = null
                Log.w("DeviceLocator", "Ringtone unavailable: ${it.javaClass.simpleName}")
            },
        )
        if (!FindPhoneAlertPolicy.wasStarted(soundStarted)) {
            notificationManager?.cancel(FIND_PHONE_NOTIFICATION_ID)
            Log.w("DeviceLocator", "Find-phone request did not start ringtone playback")
            return false
        }

        showStopNotification()
        // Fallback: notification permission can be denied while ringtone playback succeeds.
        // The fixed timeout bounds a looping alert when no visible stop action is available.
        val autoStopScheduled = logicalActionSucceeded(
            action = {
                check(mainHandler.postDelayed(autoStopRunnable, FindPhoneAlertPolicy.AUTO_STOP_DELAY_MS)) {
                    "The find-phone auto-stop could not be scheduled"
                }
            },
            onFailure = { Log.w("DeviceLocator", "Find-phone auto-stop unavailable: ${it.javaClass.simpleName}") },
        )
        if (!autoStopScheduled) {
            stopRingPhone()
            return false
        }
        return true
    }

    @Synchronized
    fun stopRingPhone() {
        mainHandler.removeCallbacks(autoStopRunnable)
        ringtone?.stop()
        ringtone = null
        notificationManager?.cancel(FIND_PHONE_NOTIFICATION_ID)
    }

    private fun showStopNotification(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = notificationManager ?: return false
        if (!manager.areNotificationsEnabled()) return false

        return logicalActionSucceeded(
            action = {
                if (Build.VERSION.SDK_INT >= 26) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            FIND_PHONE_CHANNEL_ID,
                            context.getString(R.string.find_phone_channel_name),
                            NotificationManager.IMPORTANCE_HIGH,
                        ).apply {
                            description = context.getString(R.string.find_phone_channel_description)
                            setSound(null, null)
                            enableVibration(false)
                        },
                    )
                    val channel = checkNotNull(manager.getNotificationChannel(FIND_PHONE_CHANNEL_ID)) {
                        "The find-phone notification channel was not created"
                    }
                    check(channel.importance != NotificationManager.IMPORTANCE_NONE) {
                        "The find-phone notification channel is disabled"
                    }
                }

                val stopIntent = PendingIntent.getBroadcast(
                    context,
                    FIND_PHONE_REQUEST_CODE,
                    Intent(context, FindPhoneStopReceiver::class.java).setAction(STOP_PHONE_RING_ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = Notification.Builder(context, FIND_PHONE_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_cross)
                    .setContentTitle(context.getString(R.string.find_phone_notification_title))
                    .setContentText(context.getString(R.string.find_phone_notification_text))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .addAction(Notification.Action.Builder(null, context.getString(R.string.find_phone_stop_action), stopIntent).build())
                    .build()
                manager.notify(FIND_PHONE_NOTIFICATION_ID, notification)
            },
            onFailure = { Log.w("DeviceLocator", "Find-phone notification unavailable: ${it.javaClass.simpleName}") },
        )
    }

    internal companion object {
        const val STOP_PHONE_RING_ACTION = "com.cross.app.action.STOP_PHONE_RING"
        private const val FIND_PHONE_CHANNEL_ID = "find_phone"
        private const val FIND_PHONE_NOTIFICATION_ID = 1002
        private const val FIND_PHONE_REQUEST_CODE = 1001
    }
}

internal object FindPhoneAlertPolicy {
    const val AUTO_STOP_DELAY_MS = 30_000L

    fun wasStarted(soundStarted: Boolean): Boolean = soundStarted
}
