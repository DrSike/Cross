package com.cross.app.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.cross.app.CrossApplication
import com.cross.app.MainActivity
import com.cross.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps the phone-side GATT server alive while Cross is not visible. */
class CrossBleForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false

    private val runtime
        get() = (application as CrossApplication).runtime

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch {
            runtime.ble.connection.collectLatest { connection ->
                if (foregroundStarted) updateNotification(connection)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runtime.ble.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        startAsForeground()
        runtime.ble.start()
        return START_STICKY
    }

    override fun onDestroy() {
        runtime.ble.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        if (foregroundStarted) return
        val notification = buildNotification(runtime.ble.connection.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun updateNotification(connection: CrossBleServer.Connection) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(connection))
    }

    private fun buildNotification(connection: CrossBleServer.Connection): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cross)
            .setContentTitle(getString(R.string.ble_service_notification_title))
            .setContentText(connectionLabel(connection))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ble_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.ble_service_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun connectionLabel(connection: CrossBleServer.Connection): String = when (connection.status) {
        CrossBleServer.Status.CONNECTED -> getString(R.string.ble_service_connected)
        CrossBleServer.Status.ADVERTISING -> getString(R.string.ble_service_advertising)
        CrossBleServer.Status.STARTING -> getString(R.string.ble_service_starting)
        CrossBleServer.Status.ERROR -> connection.detail ?: getString(R.string.ble_service_error)
        CrossBleServer.Status.STOPPED -> getString(R.string.ble_service_stopped)
    }

    companion object {
        private const val CHANNEL_ID = "cross_ble_connection"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.cross.app.action.START_BLE"
        const val ACTION_STOP = "com.cross.app.action.STOP_BLE"

        fun intent(context: Context, action: String): Intent =
            Intent(context, CrossBleForegroundService::class.java).setAction(action)
    }
}
