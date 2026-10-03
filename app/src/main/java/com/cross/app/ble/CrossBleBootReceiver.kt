package com.cross.app.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.cross.app.CrossApplication

/** Restarts the user-enabled BLE transport after Android finishes booting. */
class CrossBleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val runtime = (context.applicationContext as CrossApplication).runtime
        if (runtime.shouldStartBleService()) {
            ContextCompat.startForegroundService(
                context,
                CrossBleForegroundService.intent(context, CrossBleForegroundService.ACTION_START),
            )
        }
    }
}
