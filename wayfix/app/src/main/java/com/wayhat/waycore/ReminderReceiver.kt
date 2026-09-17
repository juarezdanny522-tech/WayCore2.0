package com.wayhat.waycore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val service = Intent(context, KarbysService::class.java).setAction(KarbysService.ACTION_REMINDER)
            .putExtra("label", intent?.getStringExtra("label") ?: "Tienes un recordatorio.")
        androidx.core.content.ContextCompat.startForegroundService(context, service)
    }
}
