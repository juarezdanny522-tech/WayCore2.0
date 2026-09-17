package com.wayhat.waycore

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class WayCoreService : Service() {
    private val channelId = "waycore_active"

    override fun onCreate() {
        super.onCreate()

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "WayCore activo",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WayCore activo")
            .setContentText("Karbys está listo para WayHat.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
