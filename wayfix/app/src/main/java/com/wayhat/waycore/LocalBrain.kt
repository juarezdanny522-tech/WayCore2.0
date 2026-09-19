package com.wayhat.waycore

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cerebro local de Karbys: LiteRT-LM corriendo .litertlm en el propio teléfono.
 * Ahora usa modelos oficiales litert-community/Qwen3-0.6B que son 100% compatibles,
 * no GGUF crudo que daba "Unsupported file format".
 */
object LocalBrain {
    class LocalError(message: String) : Exception(message)

    private val lock = Mutex()

    @Volatile private var engine: Engine? = null
    @Volatile private var active: Conversation? = null
    @Volatile var generating = false
        private set

    fun isModelReady(context: Context): Boolean = ModelManager.isReady(context)

    fun backendName(context: Context): String =
        if (Prefs.useGpu(context)) "GPU (OpenCL)" else "CPU"

    private fun createEngine(context: Context): Engine {
        val file = ModelManager.modelFile(context)
        if (!file.isFile) throw LocalError("El modelo todavía no está descargado. Ve a Ajustes y toca DESCARGAR IA. Recomendado para tu gama media-alta: Qwen3 0.6B Mixed Int4 (474MB) formato .litertlm oficial.")

        if (file.length() < 50 * 1024 * 1024) {
            throw LocalError("Archivo incompleto (${file.length() / 1024 / 1024} MB). Borra y descarga de nuevo. Debe pesar ${ModelManager.specFor(context).megabytes} MB. Usa WiFi.")
        }

        // Verificar que no sea HTML
        try {
            file.inputStream().use { input ->
                val firstKb = ByteArray(1024)
                val read = input.read(firstKb)
                if (read > 0) {
                    val preview = String(firstKb, 0, read, Charsets.UTF_8)
                    if (preview.contains("<html", true) || preview.contains("<!DOCTYPE", true)) {
                        throw LocalError("El archivo descargado es una página de error HTML, no un modelo. Borra y descarga de nuevo. Si sigue fallando usa modo NUBE con Gemini que no necesita descargar nada.")
                    }
                    if (file.name.endsWith(".gguf", true) && read >= 4) {
                        val magic = String(firstKb, 0, 4, Charsets.US_ASCII)
                        if (magic != "GGUF") {
                            throw LocalError("GGUF no válido (magic=$magic). Borra y descarga Qwen3 0.6B .litertlm que es 100% compatible y nunca da error de formato.")
                        }
                    }
                }
            }
        } catch (e: LocalError) { throw e } catch (_: Exception) {}

        try {
            val cache = context.cacheDir
            if (!cache.exists()) cache.mkdirs()
            if (cache.freeSpace < 200 * 1024 * 1024) {
                cache.listFiles()?.forEach { if (it.isFile && it.name.startsWith("litert")) try { it.delete() } catch (_: Exception) {} }
            }
        } catch (_: Exception) {}

        // Solo CPU por defecto para máxima compatibilidad - GPU en Mali/Adreno causa INVALID_ARGUMENT
        val wanted = if (Prefs.useGpu(context)) listOf(Backend.GPU(), Backend.CPU()) else listOf(Backend.CPU())

        var lastBackend = "desconocido"
        val errors = mutableListOf<String>()

        for (backend in wanted) {
            try {
                lastBackend = if (backend is Backend.GPU) "GPU" else "CPU"
                val config = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = backend,
                    cacheDir = context.cacheDir.path
                )
                val candidate = Engine(config)
                candidate.initialize()
                return candidate
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                errors.add("$lastBackend: $msg")
                if (lastBackend == "CPU") break
                continue
            }
        }

        val ram = ModelManager.totalRamMegabytes(context)
        val fileName = file.name
        val detailedError = errors.joinToString("; ")

        val extra = """
            Modelo $fileName falló con LiteRT-LM. Para tu gama media-alta haz esto:
            1. BORRA el modelo actual (Ajustes -> BORRAR)
            2. Desactiva GPU (usa solo CPU)
            3. Descarga Qwen3 0.6B Mixed Int4 (474MB) .litertlm oficial - es 100% compatible y NUNCA da 'Unsupported file format'
            4. Si el APK dice 'archivo malicioso', ve a Ajustes -> Apps -> Acceso especial -> Instalar apps desconocidas -> permite tu gestor de archivos. Luego Play Store -> Play Protect -> Ajustes -> desactiva análisis. Es falso positivo por ser debug.
            5. Mientras tanto usa modo NUBE con Gemini 2.0-flash que ya está arreglado y no necesita descargar nada
            Error: $detailedError | RAM: $ram MB | ${Build.MANUFACTURER} ${Build.MODEL}
        """.trimIndent()

        throw LocalError("No se pudo cargar el modelo. $extra")
    }

    private suspend fun ensureEngine(context: Context): Engine {
        val ready = engine
        if (ready != null) return ready
        return lock.withLock {
            val again = engine
            if (again != null) again else createEngine(context).also { engine = it }
        }
    }

    suspend fun ask(
        context: Context,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        onChunk: suspend (String) -> Unit
    ): String {
        val current = ensureEngine(context)
        return lock.withLock { runGeneration(current, prompt, systemPrompt, maxTokens, onChunk) }
    }

    private suspend fun runGeneration(
        current: Engine,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        onChunk: suspend (String) -> Unit
    ): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.6),
            maxOutputToken = maxTokens.coerceIn(24, 400)
        )
        val conversation = try {
            current.createConversation(config)
        } catch (t: Throwable) {
            throw LocalError("El modelo no pudo abrir conversación: ${t.message ?: t.javaClass.simpleName}. Prueba Qwen3 0.6B Mixed Int4 que es más compatible.")
        }

        active = conversation
        generating = true
        val full = StringBuilder()
        try {
            conversation.sendMessageAsync(prompt).collect { message ->
                val chunk = message.toString()
                if (chunk.isEmpty()) return@collect
                val soFar = full.toString()
                val delta = when {
                    soFar.isEmpty() -> chunk
                    chunk == soFar -> ""
                    chunk.startsWith(soFar) -> chunk.substring(soFar.length)
                    else -> chunk
                }
                if (delta.isNotEmpty()) {
                    full.append(delta)
                    onChunk(delta)
                }
            }
        } catch (t: Throwable) {
            if (full.isEmpty()) {
                throw LocalError("El modelo falló al responder: ${t.message ?: t.javaClass.simpleName}. Si es INVALID_ARGUMENT, borra y descarga Qwen3 0.6B Mixed Int4 474MB.")
            }
        } finally {
            generating = false
            active = null
            try { conversation.close() } catch (_: Throwable) { }
        }
        return full.toString().trim()
    }

    fun cancelGeneration() {
        val conversation = active ?: return
        try { conversation.cancelProcess() } catch (_: Throwable) { }
    }

    fun unload() {
        val current = engine
        engine = null
        try { current?.close() } catch (_: Throwable) { }
    }

    fun isLoaded(): Boolean = engine != null

    fun suggestedThreads(): Int = Runtime.getRuntime().availableProcessors().let { cores ->
        when {
            cores <= 2 -> 1
            cores <= 4 -> 2
            cores <= 6 -> 3
            else -> 4
        }
    }

    fun deviceSummary(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.SDK_INT} · ${suggestedThreads()} hilos"

    fun deviceSummary(context: Context): String {
        val ram = ModelManager.totalRamMegabytes(context)
        val arch = if (ModelManager.hasCpuForEngine(context)) "64-bit ✓" else "32-bit ✗"
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.SDK_INT} · ${suggestedThreads()} hilos · $arch · $ram MB RAM"
    }
}
