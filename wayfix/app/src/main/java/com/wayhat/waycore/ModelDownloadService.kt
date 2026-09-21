package com.wayhat.waycore

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Descarga el modelo .task en segundo plano con notificación de progreso.
 *
 * - Reanuda descargas interrumpidas (HTTP Range) para redes móviles inestables.
 * - Valida el archivo final (tamaño + firma ZIP "PK").
 * - Informa a la UI con broadcasts ACTION_MODEL_EVENT.
 */
class ModelDownloadService : Service() {

    companion object {
        const val ACTION_DOWNLOAD = "com.wayhat.waycore.MODEL_DOWNLOAD"
        const val ACTION_CANCEL = "com.wayhat.waycore.MODEL_CANCEL"
        const val ACTION_MODEL_EVENT = "com.wayhat.waycore.MODEL_EVENT"

        const val EXTRA_URL = "url"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_EXPECTED = "expected_bytes"

        const val EXTRA_EVENT = "event" // started|progress|done|error|cancelled
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MESSAGE = "message"

        const val EVENT_STARTED = "started"
        const val EVENT_PROGRESS = "progress"
        const val EVENT_DONE = "done"
        const val EVENT_ERROR = "error"
        const val EVENT_CANCELLED = "cancelled"

        @Volatile var downloading = false
            private set

        fun downloadIntent(ctx: android.content.Context, spec: ModelSpec, token: String): Intent =
            Intent(ctx, ModelDownloadService::class.java).setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_URL, spec.url)
                .putExtra(EXTRA_FILENAME, spec.fileName)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_EXPECTED, spec.sizeBytes)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "waycore_model_download"
    private val notifId = 2501

    @Volatile private var cancelled = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // Sin límite: redes lentas pero vivas no deben abortar.
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Descarga de IA local", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
            }
            ACTION_DOWNLOAD -> {
                if (downloading) {
                    event(EVENT_PROGRESS, -1, "Ya hay una descarga en curso.")
                } else {
                    val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                    val filename = intent.getStringExtra(EXTRA_FILENAME).orEmpty()
                        .ifBlank { "modelo-local.task" }
                    val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                    val expected = intent.getLongExtra(EXTRA_EXPECTED, 0L)
                    if (url.isBlank()) {
                        event(EVENT_ERROR, -1, "URL de descarga vacía.")
                        stopSelf()
                    } else {
                        cancelled = false
                        startForegroundCompat("Preparando descarga…", null)
                        scope.launch { runDownload(url, filename, token, expected) }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runDownload(url: String, filename: String, token: String, expected: Long) {
        downloading = true
        try {
            val dir = LocalModelManager.modelDir(this)
            val tmp = File(dir, "$filename.tmp")
            val finalFile = File(dir, filename)

            var done = if (tmp.exists()) tmp.length() else 0L
            if (done > 0 && expected > 0 && done >= expected) {
                // Parcial del tamaño esperado o mayor: no se puede reanudar, empezar de cero.
                tmp.delete()
                done = 0L
            }
            val need = if (expected > 0) expected else 400L * 1024L * 1024L
            if (!LocalModelManager.hasUsableSpace(this, need - done + 100L * 1024L * 1024L)) {
                fail("Sin espacio libre. Libera al menos ${LocalModelManager.formatMB(need)} e inténtalo de nuevo.")
                return
            }

            event(EVENT_STARTED, 0, "Descargando $filename…")
            updateNotification("Descargando modelo de IA…", null)

            val reqBuilder = Request.Builder().url(url)
                .header("User-Agent", "WayCore/0.4.0 (Android)")
                .header("Accept", "*/*")
            if (token.isNotBlank()) reqBuilder.header("Authorization", "Bearer $token")
            if (done > 0) reqBuilder.header("Range", "bytes=$done-")

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val code = resp.code
                if (code == 401 || code == 403) {
                    tmp.delete()
                    fail(
                        "Descarga rechazada ($code): este modelo pide aceptar su licencia en HuggingFace y usar un token. " +
                            "Pega tu token en la app o importa el archivo manualmente. Ver la guía IA_LOCAL."
                    )
                    return
                }
                if (code == 404) {
                    fail("No se encontró el archivo en el servidor (404). Revisa la URL del modelo.")
                    return
                }
                if (code == 416 && done > 0) {
                    tmp.delete()
                    fail("La descarga parcial no coincide con el servidor. Pulsa DESCARGAR de nuevo para empezar de cero.")
                    return
                }
                if (code != 200 && code != 206) {
                    fail("El servidor respondió $code. Inténtalo de nuevo más tarde.")
                    return
                }
                if (code == 200 && done > 0) {
                    // El servidor ignoró el Range: empezar de cero.
                    done = 0L
                    tmp.delete()
                }
                val body = resp.body ?: run {
                    fail("Respuesta vacía del servidor.")
                    return
                }
                val chunkTotal = body.contentLength()
                val grandTotal = if (chunkTotal > 0) done + chunkTotal else expected
                var lastNotif = 0L
                var lastPct = -1

                body.byteStream().use { ins ->
                    FileOutputStream(tmp, done > 0).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (cancelled) {
                                event(EVENT_CANCELLED, lastPct, "Descarga cancelada. Puedes reanudarla después.")
                                updateNotification("Descarga cancelada", null)
                                stopSelf()
                                return
                            }
                            val n = try { ins.read(buf) } catch (e: Exception) {
                                fail("Conexión interrumpida (${e.message ?: "red"}). Reanuda la descarga; no se pierde lo avanzado.")
                                return
                            }
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (grandTotal > 0) {
                                val pct = ((done * 100) / grandTotal).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    event(
                                        EVENT_PROGRESS, pct,
                                        "Descargando… ${LocalModelManager.formatMB(done)} de ${LocalModelManager.formatMB(grandTotal)} ($pct%)"
                                    )
                                }
                            } else {
                                event(EVENT_PROGRESS, -1, "Descargando… ${LocalModelManager.formatMB(done)}")
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastNotif > 900) {
                                lastNotif = now
                                updateNotification(
                                    if (grandTotal > 0) "Descargando modelo… $lastPct%"
                                    else "Descargando modelo… ${LocalModelManager.formatMB(done)}",
                                    if (grandTotal > 0) lastPct else null
                                )
                            }
                        }
                    }
                }
            }

            // Validación final.
            if (!LocalModelManager.looksLikeTaskBundle(tmp)) {
                // ¿Nos devolvieron un JSON/HTML de error en vez del modelo?
                val hint = try {
                    val head = ByteArray(160)
                    tmp.inputStream().use { it.read(head) }
                    val s = String(head).trim()
                    if (s.startsWith("{") || s.startsWith("<")) " El servidor devolvió un mensaje en lugar del modelo (¿falta el token?)." else ""
                } catch (_: Exception) { "" }
                tmp.delete()
                fail("El archivo descargado no es un modelo válido.$hint")
                return
            }
            if (finalFile.exists()) finalFile.delete()
            val moved = try {
                if (!tmp.renameTo(finalFile)) {
                    tmp.copyTo(finalFile, overwrite = true)
                    tmp.delete()
                }
                true
            } catch (_: Exception) { false }
            if (!moved || !finalFile.exists()) {
                fail("No se pudo guardar el modelo en el celular.")
                return
            }
            event(EVENT_DONE, 100, "Modelo listo: ${finalFile.name} (${LocalModelManager.formatMB(finalFile.length())}). Ya funciona sin internet.")
            updateNotification("Modelo de IA listo", 100)
        } catch (e: Exception) {
            fail("Descarga fallida: ${e.message ?: "error de red"}. Reanúdala cuando tengas mejor señal.")
        } finally {
            downloading = false
            stopSelf()
        }
    }

    private fun fail(message: String) {
        event(EVENT_ERROR, -1, message)
        updateNotification("Descarga detenida", null)
    }

    private fun event(kind: String, progress: Int, message: String) {
        try {
            sendBroadcast(
                Intent(ACTION_MODEL_EVENT).setPackage(packageName)
                    .putExtra(EXTRA_EVENT, kind)
                    .putExtra(EXTRA_PROGRESS, progress)
                    .putExtra(EXTRA_MESSAGE, message)
            )
        } catch (_: Exception) { }
    }

    private fun startForegroundCompat(text: String, progress: Int?) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("WayCore · IA local")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (progress == null) setProgress(0, 0, true) else setProgress(100, progress, false)
            }
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notifId, n)
        }
    }

    private fun updateNotification(text: String, progress: Int?) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("WayCore · IA local")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .apply {
                    if (progress == null) setProgress(0, 0, true) else setProgress(100, progress, false)
                }
                .build()
            nm.notify(notifId, n)
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        cancelled = true
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
