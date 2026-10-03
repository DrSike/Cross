package com.cross.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Stops a phone-finder ringtone when the user presses the notification action. */
class FindPhoneStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DeviceLocator.STOP_PHONE_RING_ACTION) return
        (context.applicationContext as? CrossApplication)?.runtime?.stopPhoneRing()
    }
}
