package com.wayhat.waycore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Servicio en primer plano que descarga el modelo local (Qwen2.5-1.5B, ~1.6 GB)
 * y carga/descarga el motor LiteRT-LM dentro del teléfono.
 *
 * Todo es 100% local: el modelo se descarga una sola vez desde Hugging Face
 * (HTTPS) y después Karbys funciona sin Internet. No se necesita PC ni Ollama.
 *
 * El archivo del modelo se valida contra su tamaño exacto (el tamaño oficial
 * publicado por Google en el allowlist del AI Edge Gallery), así un
 * download corrupto o truncado se detecta antes de intentar cargarlo.
 */
class ModelService : Service() {
    companion object {
        const val ACTION_DOWNLOAD = "com.wayhat.waycore.MODEL_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.wayhat.waycore.MODEL_CANCEL_DOWNLOAD"
        const val ACTION_LOAD = "com.wayhat.waycore.MODEL_LOAD"
        const val ACTION_UNLOAD = "com.wayhat.waycore.MODEL_UNLOAD"
        const val ACTION_STATUS = "com.wayhat.waycore.MODEL_STATUS"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PROGRESS = "progress"

        // Modelo oficial de Google para Android (Qwen2.5-1.5B-Instruct, 8-bit,
        // 4096 de contexto, GPU+CPU). Fijado al commit exacto para que el
        // tamaño esperado sea confiable.
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/19edb84c69a0212f29a6ef17ba0d6f278b6a1614/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true"
        const val MODEL_FILE_NAME = "qwen2.5-1.5b-instruct-q8-ctx4096.litertlm"
        const val EXPECTED_MODEL_SIZE = 1597931520L // 1.49 GB (tamaño oficial exacto)

        const val STATE_NOT_DOWNLOADED = "NOT_DOWNLOADED"
        const val STATE_DOWNLOADING = "DOWNLOADING"
        const val STATE_DOWNLOAD_FAILED = "DOWNLOAD_FAILED"
        const val STATE_LOADING = "LOADING"
        const val STATE_LOADED = "LOADED"
        const val STATE_LOAD_FAILED = "LOAD_FAILED"

        @Volatile var state: String = STATE_NOT_DOWNLOADED
            private set
        @Volatile var message: String = "El modelo local no está descargado todavía."
            private set
        @Volatile var progressPct: Int = 0
            private set

        @Volatile private var instance: ModelService? = null

        fun modelFile(context: android.content.Context): File =
            File(context.filesDir, MODEL_FILE_NAME)

        fun hasModelFile(context: android.content.Context): Boolean {
            val f = modelFile(context)
            return f.exists() && f.length() == EXPECTED_MODEL_SIZE
        }

        fun setState(next: String, text: String, progress: Int = progressPct) {
            state = next
            message = text
            progressPct = progress
            instance?.broadcastStatus()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadJobRef = AtomicReference<Job?>()
    private val cancelDownload = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForegroundCompat()
        refreshInitialState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> startDownload()
            ACTION_CANCEL_DOWNLOAD -> {
                cancelDownload.set(true)
                setState(STATE_DOWNLOADING, "Cancelando descarga…")
            }
            ACTION_LOAD -> startLoad()
            ACTION_UNLOAD -> {
                scope.launch {
                    LocalQwen.unload()
                    setState(STATE_NOT_DOWNLOADED, if (hasModelFile(this@ModelService))
                        "Modelo descargado. Puedes cargarlo cuando quieras."
                        else "El modelo local no está descargado todavía.")
                }
            }
        }
        return START_STICKY
    }

    private fun refreshInitialState() {
        if (LocalQwen.isLoaded) {
            setState(STATE_LOADED, "Modelo local listo. Karbys piensa en este teléfono.")
        } else if (hasModelFile(this)) {
            setState(STATE_NOT_DOWNLOADED, "Modelo descargado. Pulsa Cargar modelo.")
        } else {
            setState(STATE_NOT_DOWNLOADED, "El modelo local no está descargado todavía.")
        }
    }

    private fun startDownload() {
        if (downloadJobRef.get()?.isActive == true) return
        val job = scope.launch {
            setState(STATE_DOWNLOADING, "Descargando modelo local (≈1.6 GB)…", 0)
            val error = try {
                downloadModel { pct ->
                    // onProgress llega con el porcentaje 0..99; solo se
                    // notifica cuando cambia (evita saturar de broadcasts).
                    if (pct != progressPct) setState(STATE_DOWNLOADING, "Descargando modelo local (≈1.6 GB)…", pct)
                }
            } catch (e: Exception) {
                "No se pudo descargar el modelo: ${e.message}. Revisa tu conexión a Internet."
            }
            downloadJobRef.set(null)
            if (error == null) {
                setState(STATE_NOT_DOWNLOADED, "Modelo descargado. Pulsa Cargar modelo.", 100)
            } else {
                setState(STATE_DOWNLOAD_FAILED, error)
            }
        }
        downloadJobRef.set(job)
    }

    /** Descarga con reanudación (HTTP Range) y validación de tamaño. Devuelve null si todo salió bien. */
    private fun downloadModel(onProgressPct: (Int) -> Unit): String? {
        val part = File(filesDir, MODEL_FILE_NAME + ".part")
        val finalFile = File(filesDir, MODEL_FILE_NAME)

        var resumeFrom = part.length()
        if (resumeFrom >= EXPECTED_MODEL_SIZE) {
            if (finalFile.exists() && finalFile.length() == EXPECTED_MODEL_SIZE) return null
            part.delete()
            resumeFrom = 0
        }

        val request = Request.Builder().url(MODEL_URL)
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .build()

        client.newCall(request).execute().use { response ->
            // Si el servidor ignora el rango y responde 200, se reinicia la descarga.
            if (response.code == 200 && resumeFrom > 0) {
                part.outputStream().use { it.write(ByteArray(0)) }
                resumeFrom = 0
            }
            if (!response.isSuccessful) {
                return "No se pudo descargar el modelo (HTTP ${response.code}). Revisa tu conexión e inténtalo de nuevo."
            }
            val body = response.body ?: return "Respuesta vacía del servidor. Inténtalo de nuevo."

            val known = body.contentLength()
            val total = if (known > 0) resumeFrom + known else EXPECTED_MODEL_SIZE
            var written = resumeFrom
            onProgressPct(0)

            val out = BufferedOutputStream(FileOutputStream(part, resumeFrom > 0))
            val buf = ByteArray(256 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    if (cancelDownload.get()) {
                        cancelDownload.set(false)
                        return "Descarga cancelada. Puedes continuar donde quedó."
                    }
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    if (total > 0) {
                        onProgressPct((written * 100 / total).toInt().coerceIn(0, 99))
                    }
                }
            }
            out.flush()
            out.close()

            if (part.length() != EXPECTED_MODEL_SIZE) {
                part.delete()
                return "El archivo descargado no coincide con el tamaño esperado. Inténtalo de nuevo."
            }
            if (finalFile.exists()) finalFile.delete()
            if (!part.renameTo(finalFile)) {
                part.copyTo(finalFile, overwrite = true)
                part.delete()
            }
            return null
        }
    }

    private fun startLoad() {
        if (state == STATE_LOADING) return
        if (!hasModelFile(this)) {
            setState(STATE_LOAD_FAILED, "Primero descarga el modelo local.")
            return
        }
        setState(STATE_LOADING, "Cargando el modelo en memoria (esto toma unos segundos)…")
        scope.launch {
            val result = LocalQwen.load(modelFile(this@ModelService).absolutePath)
            result.fold(
                onSuccess = {
                    setState(STATE_LOADED, "Modelo local listo. Karbys piensa en este teléfono.")
                },
                onFailure = { e ->
                    setState(STATE_LOAD_FAILED, "No se pudo cargar el modelo. Tu teléfono quizás no tenga suficiente memoria libre. ${e.message}")
                }
            )
        }
    }

    private fun broadcastStatus() {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra("state", state)
            .putExtra(EXTRA_MESSAGE, message)
            .putExtra(EXTRA_PROGRESS, progressPct))
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("waycore_model", "Modelo local", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        val n: Notification = NotificationCompat.Builder(this, "waycore_model")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("WayCore")
            .setContentText("Modelo local de Karbys")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2701, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(2701, n)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        cancelDownload.set(true)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
