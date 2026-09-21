package com.wayhat.waycore

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val label = intent?.getStringExtra("label") ?: "Tienes un recordatorio."
        val service = Intent(context, KarbysService::class.java).setAction(KarbysService.ACTION_REMINDER)
            .putExtra("label", label)
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, service)
        } catch (e: Exception) {
            // Android 12+: desde segundo plano no se puede arrancar un servicio
            // con micrófono. Se avisa con notificación para abrir la app.
            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel("waycore_reminders", "Recordatorios", NotificationManager.IMPORTANCE_HIGH)
                )
                val pi = PendingIntent.getActivity(
                    context, 9401, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val n = NotificationCompat.Builder(context, "waycore_reminders")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Recordatorio de Karbys")
                    .setContentText(label)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                nm.notify(9401, n)
            } catch (_: Exception) { }
        }
    }
}
