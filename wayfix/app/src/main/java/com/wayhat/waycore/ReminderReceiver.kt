package com.wayhat.waycore

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Despierta a Karbys para hablar un recordatorio.
 *
 * En Android 12+ un receiver que corre en segundo plano no siempre puede arrancar
 * un foreground service (ForegroundServiceStartNotAllowedException), así que se
 * intenta primero el aviso hablado y, si el sistema lo rechaza, se deja una
 * notificación para que la persona lo escuche al abrir la app o al deslizar la barra.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val label = intent?.getStringExtra("label") ?: "Tienes un recordatorio."
        val service = Intent(context, KarbysService::class.java)
            .setAction(KarbysService.ACTION_REMINDER)
            .putExtra("label", label)
        try {
            ContextCompat.startForegroundService(context, service)
        } catch (_: Exception) {
            notify(context, label)
        }
    }

    private fun notify(context: Context, label: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(KarbysService.CHANNEL, "Karbys activo", NotificationManager.IMPORTANCE_HIGH)
        )
        val notification = NotificationCompat.Builder(context, KarbysService.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recordatorio de Karbys")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        try { manager.notify(KarbysService.NOTIFICATION_ID + 1, notification) } catch (_: Exception) { }
    }
}
