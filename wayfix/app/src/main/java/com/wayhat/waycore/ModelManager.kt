package com.wayhat.waycore

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ModelProgress(val downloaded: Long, val total: Long, val bytesPerSecond: Double, val phase: String)

/**
 * Un GGUF descargable. Los tamaños y SHA-256 son los públicos del repo de Qwen en
 * Hugging Face (leídos de la API `/api/models/.../tree/main`), no estimaciones: sirven
 * para reanudar y, sobre todo, para rechazar un archivo truncado. Un GGUF incompleto no
 * da un error bonito: tumba el runtime nativo y Karbys se queda mudo sin explicación.
 */
data class ModelSpec(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val label: String,
    val minRamMegabytes: Long
) {
    val megabytes: Long get() = sizeBytes / (1024L * 1024L)
}

/**
 * El modelo se descarga al teléfono en vez de ir dentro del APK: el binario de 1.5B pesa
 * más de un gigabyte y el instalador seguiría pesando unos pocos megas.
 */
object ModelManager {
    const val ACTION_PROGRESS = "com.wayhat.waycore.MODEL_PROGRESS"
    const val EXTRA_PERCENT = "percent"
    const val EXTRA_PHASE = "phase"
    const val EXTRA_TEXT = "text"

    private const val HF_15B = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main"
    private const val HF_05B = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main"

    val SPECS: List<ModelSpec> = listOf(
        ModelSpec(
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "$HF_15B/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1117320736L,
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            label = "Qwen 1.5B Q4_K_M, la recomendada: es la que mejor entiende las órdenes del sombrero",
            minRamMegabytes = 3600L
        ),
        ModelSpec(
            fileName = "qwen2.5-1.5b-instruct-q3_k_m.gguf",
            url = "$HF_15B/qwen2.5-1.5b-instruct-q3_k_m.gguf",
            sizeBytes = 924455968L,
            sha256 = "58cb5c05ecef48e82961f1a2be6544145ea26136f69dddda4bbbd092f0e4b993",
            label = "Qwen 1.5B Q3, algo menos precisa, casi doscientos megas más liviana",
            minRamMegabytes = 3200L
        ),
        ModelSpec(
            fileName = "qwen2.5-1.5b-instruct-q2_k.gguf",
            url = "$HF_15B/qwen2.5-1.5b-instruct-q2_k.gguf",
            sizeBytes = 752880160L,
            sha256 = "5ede348e91ce1e7a330926ec5b202c27b864d065149dc463257fde1f98865b3a",
            label = "Qwen 1.5B Q2, para celulares con poca memoria; obedece menos órdenes",
            minRamMegabytes = 2900L
        ),
        ModelSpec(
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            url = "$HF_05B/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 491400032L,
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            label = "Qwen 0.5B, rapidísima y liviana, pero solo entiende los comandos locales",
            minRamMegabytes = 2500L
        )
    )

    val DEFAULT: ModelSpec get() = SPECS.first()

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun specFor(context: Context): ModelSpec {
        val name = Prefs.modelName(context)
        return SPECS.firstOrNull { it.fileName == name } ?: DEFAULT
    }

    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val out = File(base, "models")
        if (!out.exists()) out.mkdirs()
        return out
    }

    fun modelFile(context: Context): File = File(dir(context), specFor(context).fileName)
    private fun partFile(context: Context): File = File(dir(context), specFor(context).fileName + ".part")

    /** Tamaño esperado: el del catálogo, o el que escribió el usuario para una URL propia. */
    fun expectedSize(context: Context): Long {
        val custom = Prefs.modelSize(context)
        if (custom > 0L) return custom
        return if (Prefs.modelUrl(context).isBlank() || Prefs.modelUrl(context) == specFor(context).url) {
            specFor(context).sizeBytes
        } else -1L
    }

    fun expectedSha(context: Context): String {
        val custom = Prefs.modelSha(context)
        if (custom.isNotBlank()) return custom
        return if (Prefs.modelUrl(context).isBlank() || Prefs.modelUrl(context) == specFor(context).url) {
            specFor(context).sha256
        } else ""
    }

    fun isReady(context: Context): Boolean {
        val file = modelFile(context)
        if (!file.isFile) return false
        val expected = expectedSize(context)
        return expected <= 0L || file.length() == expected
    }

    fun sizeOnDisk(context: Context): Long = modelFile(context).length()

    fun freeMegabytes(context: Context): Long = try {
        val stat = android.os.StatFs(dir(context).absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong / (1024L * 1024L)
    } catch (_: Exception) { Long.MAX_VALUE / 4 }

    fun totalRamMegabytes(context: Context): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024L * 1024L)
    } catch (_: Exception) { 0L }

    /**
     * Un 1.5B en Q4 ocupa alrededor de 1.2 GB entre pesos mapeados y contexto. Si el
     * teléfono tiene menos RAM total que la que pide el modelo, Android mata el proceso a
     * media respuesta: es mejor decirlo antes de gastar un gigabyte de datos.
     */
    /**
     * El motor nativo solo viene para 64 bits (en el APK hay liblitertlm_jni.so para
     * arm64-v8a; para armeabi-v7a no existe). Comprobarlo antes de descargar evita gastar un
     * gigabyte de datos para descubrir después que el teléfono no puede cargarlo.
     */
    fun hasCpuForEngine(context: Context): Boolean =
        Build.SUPPORTED_ABIS.any { it.startsWith("arm64") || it == "x86_64" || it == "riscv64" }

    fun fitsOnThisPhone(context: Context): Boolean =
        hasCpuForEngine(context) && totalRamMegabytes(context) >= specFor(context).minRamMegabytes

    fun reasonItDoesNotFit(context: Context): String {
        val ram = totalRamMegabytes(context)
        val needed = specFor(context).minRamMegabytes
        return when {
            !hasCpuForEngine(context) ->
                "Este teléfono es de 32 bits y el motor de IA local solo corre en 64 bits. Karbys puede seguir usando la nube."
            ram in 1 until needed ->
                "Este teléfono tiene $ram megabytes de memoria y el modelo pide unos $needed. Prueba con la versión Q2 o con la de quinientos millones de parámetros."
            else -> "No hay suficiente sitio libre para el modelo."
        }
    }

    fun megabytes(bytes: Long): String = (bytes / (1024L * 1024L)).toString()

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Descarga reanudable: si la conexión cae, se sigue desde el byte que iba. */
    suspend fun download(context: Context, onProgress: (ModelProgress) -> Unit): String =
        withContext(Dispatchers.IO) {
            val target = modelFile(context)
            if (isReady(context)) {
                onProgress(ModelProgress(target.length(), target.length(), 0.0, "listo"))
                return@withContext target.absolutePath
            }

            val url = Prefs.modelUrl(context).ifBlank { specFor(context).url }
            val part = partFile(context)
            val expected = expectedSize(context)
            var resumeFrom = if (part.exists()) part.length() else 0L
            if (expected > 0L && resumeFrom >= expected) {
                part.delete()
                resumeFrom = 0L
            }

            val request = Request.Builder().url(url).apply {
                if (resumeFrom > 0L) addHeader("Range", "bytes=$resumeFrom-")
                addHeader("Accept-Encoding", "identity")
            }.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw java.io.IOException("Hugging Face respondió el código ${response.code}")
                }
                val body = response.body ?: throw java.io.IOException("El servidor no devolvió el archivo")

                // Si el servidor ignora el Range hay que empezar el archivo de cero, no pegar.
                val appending = response.code == 206 && resumeFrom > 0L
                if (!appending) {
                    resumeFrom = 0L
                    part.delete()
                }

                val contentLength = if (body.contentLength() >= 0) body.contentLength() else 0L
                val total = when {
                    expected > 0L -> expected
                    contentLength > 0L -> resumeFrom + contentLength
                    else -> -1L
                }

                FileOutputStream(part, appending).use { output ->
                    val input = body.byteStream()
                    val buffer = ByteArray(1 shl 16)
                    var done = resumeFrom
                    var markAt = System.currentTimeMillis()
                    var markBytes = 0L
                    var flushedAt = System.currentTimeMillis()
                    onProgress(ModelProgress(done, total, 0.0, if (appending) "reanudando" else "descargando"))
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        markBytes += read
                        val now = System.currentTimeMillis()
                        if (now - markAt >= 500) {
                            val speed = markBytes * 1000.0 / (now - markAt).coerceAtLeast(1)
                            onProgress(ModelProgress(done, total, speed, "descargando"))
                            markAt = now
                            markBytes = 0
                        }
                        // Volcar cada 5 s deja el .part utilizable aunque la app muera.
                        if (now - flushedAt >= 5000) {
                            output.flush()
                            flushedAt = now
                        }
                    }
                    output.flush()
                }
            }

            if (expected > 0L && part.length() != expected) {
                part.delete()
                throw java.io.IOException("El archivo quedó incompleto, con ${part.length()} de $expected bytes. Vuelve a intentarlo y sigo donde lo dejé.")
            }

            val expectedSha = expectedSha(context)
            if (expectedSha.isNotBlank()) {
                onProgress(ModelProgress(part.length(), expected.coerceAtLeast(part.length()), 0.0, "verificando"))
                val actual = sha256Of(part)
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    part.delete()
                    throw java.io.IOException("La firma del archivo no coincide con la de Qwen; lo borré para descargarlo otra vez.")
                }
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw java.io.IOException("No se pudo poner el archivo en su lugar final.")
            Prefs.markModelVerified(context, true)
            onProgress(ModelProgress(target.length(), target.length(), 0.0, "listo"))
            target.absolutePath
        }

    suspend fun deleteModel(context: Context) = withContext(Dispatchers.IO) {
        for (spec in SPECS) {
            try { File(dir(context), spec.fileName).delete() } catch (_: Exception) { }
            try { File(dir(context), spec.fileName + ".part").delete() } catch (_: Exception) { }
        }
        Prefs.markModelVerified(context, false)
        LocalBrain.unload()
    }
}
