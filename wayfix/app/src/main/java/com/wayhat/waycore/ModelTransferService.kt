package com.wayhat.waycore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Descarga el GGUF en segundo plano con notificación de progreso.
 *
 * Es un servicio `dataSync` porque una descarga de ~470 MB con datos móviles se interrumpe
 * al apagar la pantalla: aquí la descarga sigue, se reanuda sola con Range y Karbys va
 * dictando el porcentaje para no tener que mirar la pantalla.
 */
class ModelTransferService : Service() {
    companion object {
        const val ACTION_START = "com.wayhat.waycore.MODEL_START"
        const val ACTION_CANCEL = "com.wayhat.waycore.MODEL_CANCEL"
        private const val CHANNEL = "model_download"
        private const val NOTIFICATION_ID = 2602

        @Volatile private var busy = false
        fun isBusy(): Boolean = busy

        /**
         * Arranca o cancela la descarga. Se pasa el contexto de la activity, que está en
         * primer plano, porque Android 12+ no deja levantar un servicio en segundo plano desde
         * la nada; si aun así lo rechaza, la interfaz sigue mostrando el progreso real del disco.
         */
        fun start(context: Context) = launch(context, ACTION_START)

        fun cancel(context: Context) = launch(context, ACTION_CANCEL)

        private fun launch(context: Context, action: String) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ModelTransferService::class.java).setAction(action)
                )
            } catch (_: Exception) { }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastAnnouncedPercent = -25

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Descarga del modelo", NotificationManager.IMPORTANCE_LOW)
        )
        show(0, "Preparando la descarga", ongoing = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                show(0, "Descarga cancelada; se reanuda donde quedó", ongoing = false)
                stopSelfSafely()
            }
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (job?.isActive == true) return
        val free = ModelManager.freeMegabytes(this)
        val needed = (ModelManager.expectedSize(this).coerceAtLeast(0L)) / (1024L * 1024L)
        if (needed > 0L && free in 1 until (needed + 150)) {
            show(0, "Falta espacio libre", ongoing = false)
            say("Necesito unos $needed megabytes libres y solo hay $free. Libera espacio e inténtalo otra vez.")
            stopSelfSafely()
            return
        }

        busy = true
        lastAnnouncedPercent = -25
        say("Empiezo a bajar el cerebro de $needed megabytes. Puedes seguir usando el sombrero mientras tanto.")
        job = scope.launch {
            try {
                val path = ModelManager.download(this@ModelTransferService) { progress -> onProgress(progress) }
                show(100, "Modelo listo", ongoing = false)
                broadcast(100, "listo", "Modelo instalado en el teléfono")
                say("Listo, el modelo ya está en el teléfono en ${path.substringAfterLast('/')}. Karbys puede funcionar sin Internet.")
            } catch (e: CancellationException) {
                show(0, "Descarga detenida", ongoing = false)
            } catch (e: Exception) {
                val message = e.message ?: "La descarga falló"
                show(0, "Falló la descarga", ongoing = false)
                broadcast(-1, "error", message)
                say("No pude terminar la descarga: $message. Vuelve a intentarlo y sigo desde donde me quedé.")
            } finally {
                busy = false
                stopSelfSafely()
            }
        }
    }

    private fun onProgress(progress: ModelProgress) {
        val percent = if (progress.total > 0L) {
            (progress.downloaded.toDouble() * 100.0 / progress.total.toDouble()).roundToInt().coerceIn(0, 100)
        } else 0

        val megabytes = ((progress.bytesPerSecond / 1048576.0) * 10.0).roundToInt() / 10.0
        val label = when (progress.phase) {
            "verificando" -> "Verificando la firma del archivo"
            "reanudando" -> "Reanudando la descarga"
            "listo" -> "Modelo listo"
            else -> "${(progress.downloaded / 1048576.0).roundToInt()} de ${(progress.total / 1048576.0).roundToInt()} MB · $megabytes MB/s"
        }
        show(percent, label, ongoing = true)
        broadcast(percent, progress.phase, label)

        if (percent - lastAnnouncedPercent >= 25 && progress.phase == "descargando") {
            lastAnnouncedPercent = percent
            say("Voy al $percent por ciento.")
        }
    }

    private fun show(percent: Int, label: String, ongoing: Boolean) {
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Cerebro local de Karbys")
            .setContentText(label)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (ongoing) builder.setProgress(100, percent, percent <= 0)
        try { startForegroundCompat(builder.build()) } catch (_: Exception) { }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun broadcast(percent: Int, phase: String, text: String) {
        try {
            sendBroadcast(
                Intent(ModelManager.ACTION_PROGRESS).setPackage(packageName)
                    .putExtra(ModelManager.EXTRA_PERCENT, percent)
                    .putExtra(ModelManager.EXTRA_PHASE, phase)
                    .putExtra(ModelManager.EXTRA_TEXT, text)
            )
        } catch (_: Exception) { }
    }

    private fun say(text: String) {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, KarbysService::class.java)
                    .setAction(KarbysService.ACTION_SAY)
                    .putExtra("text", text)
            )
        } catch (_: Exception) { }
    }

    private fun stopSelfSafely() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        busy = false
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
