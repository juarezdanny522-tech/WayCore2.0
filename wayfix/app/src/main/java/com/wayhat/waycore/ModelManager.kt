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

    // Modelos OFICIALES compatibles con LiteRT-LM 0.13.1 / 0.17.1
    // Antes usábamos GGUF de Qwen que da "Unsupported file format" porque LiteRT-LM espera .litertlm
    // Ahora usamos litert-community que son modelos convertidos oficialmente para Android
    private const val HF_QWEN3_06B = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main"
    private const val HF_QWEN25_15B = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main"
    private const val HF_QWEN25_05B = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main"

    val SPECS: List<ModelSpec> = listOf(
        // Qwen3-0.6B mixed int4 - RECOMENDADO para gama media-alta, 474MB, formato .litertlm 100% compatible
        ModelSpec(
            fileName = "qwen3_0_6b_mixed_int4.litertlm",
            url = "$HF_QWEN3_06B/qwen3_0_6b_mixed_int4.litertlm",
            sizeBytes = 497000000L,
            sha256 = "",
            label = "Qwen3 0.6B Mixed Int4 (474MB) - RECOMENDADO gama media-alta, 100% compatible, nunca da error de formato",
            minRamMegabytes = 2800L
        ),
        // Qwen3-0.6B dynamic wi4b32 - más pequeño, 328MB
        ModelSpec(
            fileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            url = "$HF_QWEN3_06B/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            sizeBytes = 343000000L,
            sha256 = "",
            label = "Qwen3 0.6B Int4 (328MB) - Más pequeño, máxima compatibilidad gama media",
            minRamMegabytes = 2500L
        ),
        // Qwen3-0.6B dynamic int8 - 586MB, mejor calidad
        ModelSpec(
            fileName = "Qwen3-0.6B.litertlm",
            url = "$HF_QWEN3_06B/Qwen3-0.6B.litertlm",
            sizeBytes = 614000000L,
            sha256 = "",
            label = "Qwen3 0.6B Int8 (586MB) - Mejor calidad, para gama alta 6GB+",
            minRamMegabytes = 3200L
        ),
        // Qwen2.5-1.5B q8 - 1.6GB, formato .litertlm oficial
        ModelSpec(
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            url = "$HF_QWEN25_15B/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 1670000000L,
            sha256 = "",
            label = "Qwen2.5 1.5B Q8 (1.6GB) - Máxima calidad, solo gama alta 8GB+, formato .litertlm oficial",
            minRamMegabytes = 4000L
        ),
        // Qwen2.5-0.5B q8 task - fallback .task format (MediaPipe)
        ModelSpec(
            fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            url = "$HF_QWEN25_05B/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeBytes = 573000000L,
            sha256 = "",
            label = "Qwen2.5 0.5B Q8 .task (547MB) - Formato MediaPipe, alternativa si litertlm falla",
            minRamMegabytes = 2600L
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
    fun hasCpuForEngine(context: Context): Boolean {
        return Build.SUPPORTED_ABIS.any { abi ->
            abi.startsWith("arm64") || abi == "x86_64" || abi == "riscv64" || abi.contains("64")
        }
    }

    // Para gama media-alta permitimos descargar aunque la RAM esté justa, con advertencia.
    // Un celular de 6GB sí puede usar Q4_K_M si cierra apps.
    fun fitsOnThisPhone(context: Context): Boolean {
        if (!hasCpuForEngine(context)) return false
        val ram = totalRamMegabytes(context)
        // Solo bloqueamos si es < 2.5GB o 32-bit. Si es 3GB+ permitimos con aviso.
        if (ram in 1..2499) return false
        return true
    }

    fun reasonItDoesNotFit(context: Context): String {
        val ram = totalRamMegabytes(context)
        val needed = specFor(context).minRamMegabytes
        val free = freeMegabytes(context)
        return when {
            !hasCpuForEngine(context) ->
                "Este teléfono es de 32 bits (${Build.SUPPORTED_ABIS.joinToString()}) y el motor de IA local solo corre en 64 bits. Pero no te preocupes: Karbys puede seguir usando la nube con Gemini, que ya está arreglado para tu gama media-alta y no necesita descargar nada."
            ram in 1..2499 ->
                "Este teléfono tiene $ram MB de RAM y el modelo ${specFor(context).fileName} pide unos $needed MB. Para tu gama media-alta te recomiendo Qwen 0.5B (491 MB, pide 2500 MB) o Qwen 1.5B Q2 (752 MB, pide 2900 MB). Tócale a OTRA VERSIÓN DEL MODELO."
            free < 500 ->
                "Solo te quedan $free MB libres y necesitas ${specFor(context).megabytes} MB. Libera espacio o usa modo NUBE que no descarga nada."
            ram in 2500 until needed ->
                "Tu teléfono tiene $ram MB y el modelo pide $needed MB. En gama media-alta suele funcionar si cierras otras apps, pero si falla prueba Q2 o 0.5B. ¿Quieres que lo descargue igual? Si se cierra, cambia a 0.5B."
            else -> "No hay suficiente sitio libre para el modelo. Libera ${specFor(context).megabytes} MB o usa modo NUBE."
        }
    }

    // Sugiere el mejor modelo para este teléfono automáticamente - v0.7.3 usa .litertlm oficiales
    // Antes GGUF Q4_K_M fallaba con "Unsupported file format" incluso en gama media-alta
    // Ahora Qwen3 0.6B Mixed Int4 474MB es 100% compatible y nunca falla
    fun bestModelForThisPhone(context: Context): ModelSpec {
        val ram = totalRamMegabytes(context)
        val is64 = hasCpuForEngine(context)
        if (!is64) return SPECS[1] // Qwen3 0.6B Int4 328MB - más pequeño para 32-bit aunque use NUBE
        return when {
            ram >= 6000 -> SPECS[0] // Qwen3 0.6B Mixed Int4 474MB recomendado también para gama alta
            ram >= 4000 -> SPECS[0] // Qwen3 0.6B Mixed Int4 474MB - recomendado gama media-alta
            ram >= 3200 -> SPECS[0] // Qwen3 0.6B Mixed Int4 474MB
            ram >= 2500 -> SPECS[1] // Qwen3 0.6B Int4 328MB - más pequeño
            else -> SPECS[1] // Qwen3 0.6B Int4 328MB - más pequeño
        }
    }

    // Modelo de emergencia 100% compatible si Mixed Int4 falla - Int4 328MB más pequeño
    fun fallbackModel(): ModelSpec = SPECS[1] // Qwen3 0.6B Int4 328MB

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
                    throw java.io.IOException("Hugging Face respondió el código ${response.code}. Si es 401/403, HuggingFace está pidiendo login. Usa modo NUBE con Gemini que ya está arreglado, o prueba más tarde.")
                }
                val body = response.body ?: throw java.io.IOException("El servidor no devolvió el archivo")

                // Detectar si HuggingFace devolvió HTML en vez de GGUF (pasa cuando pide login o rate limit)
                val contentType = body.contentType()?.toString()?.lowercase() ?: ""
                if (contentType.contains("text/html")) {
                    throw java.io.IOException("HuggingFace devolvió una página HTML en vez del modelo (te está pidiendo login o te limitó). Usa modo NUBE con Gemini que funciona sin descargar nada, o intenta en WiFi más tarde. Para gama media-alta recomienda Qwen 0.5B que es más liviano.")
                }

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

                var htmlDetected = false
                FileOutputStream(part, appending).use { output ->
                    val input = body.byteStream()
                    val buffer = ByteArray(1 shl 16)
                    var done = resumeFrom
                    var markAt = System.currentTimeMillis()
                    var markBytes = 0L
                    var flushedAt = System.currentTimeMillis()
                    var firstChunkChecked = appending // Si reanuda, ya no chequear HTML
                    onProgress(ModelProgress(done, total, 0.0, if (appending) "reanudando" else "descargando"))
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break

                        // En el primer chunk, verificar si es HTML (error de HF)
                        if (!firstChunkChecked && read > 10) {
                            firstChunkChecked = true
                            val preview = String(buffer, 0, minOf(read, 500), Charsets.UTF_8)
                            if (preview.contains("<html", true) || preview.contains("<!DOCTYPE", true) || preview.trim().startsWith("{") && preview.contains("error", true)) {
                                htmlDetected = true
                                break
                            }
                            // Para .litertlm no verificamos magic GGUF, solo HTML/JSON error
                            // .litertlm es binario propio de LiteRT, no GGUF
                            if (preview.contains("not found", true) && preview.contains("error", true)) {
                                htmlDetected = true
                                break
                            }
                        }

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
                        if (now - flushedAt >= 5000) {
                            output.flush()
                            flushedAt = now
                        }
                    }
                    output.flush()
                }

                if (htmlDetected) {
                    part.delete()
                    throw java.io.IOException("HuggingFace devolvió HTML/JSON en vez del modelo GGUF. Esto pasa por rate limit o porque pide login. Usa modo NUBE (Gemini 2.0-flash ya arreglado) que no necesita descargar nada, o intenta más tarde en WiFi. Si quieres local, prueba Qwen 0.5B (491MB) que falla menos.")
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
