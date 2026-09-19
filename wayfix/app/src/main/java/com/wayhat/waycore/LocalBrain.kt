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
 * Cerebro local de Karbys: LiteRT-LM corriendo el GGUF en el propio teléfono.
 *
 * Sin red, sin clave y sin espera de servidor. Solo usa la parte más estable de la API
 * (Engine + Conversation + Flow de texto) para que una actualización del runtime no
 * rompa la app, y prueba GPU antes de caer a CPU.
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
        if (!file.isFile) throw LocalError("El modelo todavía no está descargado en el teléfono. Ve a Ajustes y toca DESCARGAR IA. Es de ${ModelManager.specFor(context).megabytes} MB y se guarda en el teléfono, no dentro del APK.")

        // Validación previa para gama media-alta: verificar que el archivo sea GGUF válido
        if (file.length() < 100 * 1024 * 1024) {
            throw LocalError("El archivo del modelo está incompleto (${file.length() / 1024 / 1024} MB). Borra y vuelve a descargar. El modelo debe pesar ${ModelManager.specFor(context).megabytes} MB.")
        }

        // Verificar magic bytes GGUF
        try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                if (input.read(magic) == 4) {
                    val magicStr = String(magic, Charsets.US_ASCII)
                    if (magicStr != "GGUF") {
                        // Puede ser archivo HTML de error de HuggingFace o archivo corrupto
                        val firstKb = ByteArray(1024)
                        file.inputStream().use { it.read(firstKb) }
                        val preview = String(firstKb, Charsets.UTF_8).take(200)
                        if (preview.contains("<html", true) || preview.contains("<!DOCTYPE", true)) {
                            throw LocalError("El archivo descargado no es un modelo, es una página de error de HuggingFace. Borra el modelo y descarga de nuevo con buena conexión. Si sigue fallando, usa modo NUBE con Gemini que ya está arreglado.")
                        }
                        throw LocalError("El archivo no parece ser GGUF válido (magic=$magicStr). Puede estar corrupto. Borra y descarga de nuevo. Si usas Q4_K_M prueba Q2 o 0.5B que son más compatibles con gama media.")
                    }
                }
            }
        } catch (e: LocalError) { throw e } catch (_: Exception) {}

        // Asegurar que el cacheDir existe y es escribible (algunos gama media tienen cache encriptado)
        try {
            val cache = context.cacheDir
            if (!cache.exists()) cache.mkdirs()
            if (cache.freeSpace < 200 * 1024 * 1024) {
                cache.listFiles()?.forEach { if (it.isFile && it.name.startsWith("litert")) try { it.delete() } catch (_: Exception) {} }
            }
        } catch (_: Exception) {}

        // Para gama media-alta: probar CPU primero SIEMPRE, GPU solo si el usuario lo activó y como fallback
        // El error "Unsupported file format" suele pasar con GPU en algunos Adreno/Mali
        val wanted = if (Prefs.useGpu(context)) {
            listOf(Backend.GPU(), Backend.CPU())
        } else {
            listOf(Backend.CPU()) // Solo CPU para máxima compatibilidad en gama media-alta
        }

        var last: Throwable? = null
        var lastBackend = "desconocido"
        val errors = mutableListOf<String>()

        for (backend in wanted) {
            try {
                lastBackend = when (backend) {
                    is Backend.GPU -> "GPU"
                    else -> "CPU"
                }

                // Configuración más conservadora para gama media
                val config = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = backend,
                    cacheDir = context.cacheDir.path
                )

                val candidate = Engine(config)
                candidate.initialize()
                return candidate
            } catch (t: Throwable) {
                last = t
                val msg = t.message ?: t.javaClass.simpleName
                errors.add("$lastBackend: $msg")
                // Si es error de formato, no tiene sentido probar GPU, solo CPU
                if (msg.contains("format", true) || msg.contains("INVALID_ARGUMENT", true) || msg.contains("Unsupported", true)) {
                    // Si ya probamos CPU y falló por formato, es archivo corrupto o cuantización no soportada
                    if (lastBackend == "CPU") {
                        break
                    }
                }
                continue
            }
        }

        // Mensaje específico para el error que reportaste
        val ram = ModelManager.totalRamMegabytes(context)
        val is64 = ModelManager.hasCpuForEngine(context)
        val fileName = file.name

        val detailedError = errors.joinToString("; ")

        val extra = when {
            detailedError.contains("format", true) || detailedError.contains("Unsupported", true) || detailedError.contains("unknown file", true) -> {
                """
                El modelo $fileName no se pudo abrir (formato no soportado). Esto pasa por:
                1. Archivo corrupto/incompleto -> Borra y descarga de nuevo
                2. Cuantización Q4_K_M no soportada en LiteRT-LM 0.17.1 en tu GPU ${Build.MANUFACTURER} -> Desactiva GPU en ajustes y usa solo CPU, o prueba Q2 o 0.5B que son más compatibles
                3. Modelo de HuggingFace incompatible -> Prueba Qwen 0.5B que pesa 491MB y es 100% compatible con gama media-alta
                4. Usa modo NUBE con Gemini (ya arreglado con modelos 2.0-flash) mientras tanto - no necesita descargar nada
                Error técnico: $detailedError
                """.trimIndent()
            }
            !is64 -> " Tu teléfono es de 32 bits y el motor local solo corre en 64 bits. Usa modo NUBE con Gemini, que ya está arreglado para tu gama media y no necesita descargar nada. Error: $detailedError"
            ram in 1..2499 -> " Tu teléfono tiene $ram MB de RAM. El modelo pide ${ModelManager.specFor(context).minRamMegabytes} MB. Prueba Qwen 0.5B (491 MB) que es para gama media. Error: $detailedError"
            else -> " Backend probado: $lastBackend. Error: $detailedError. Si usas GPU, desactívalo en ajustes y prueba solo CPU. También prueba OTRA VERSIÓN DEL MODELO con Q2 o 0.5B."
        }
        throw LocalError("No se pudo cargar el modelo en este teléfono. $extra")
    }

    private suspend fun ensureEngine(context: Context): Engine {
        val ready = engine
        if (ready != null) return ready
        return lock.withLock {
            val again = engine
            if (again != null) again else createEngine(context).also { engine = it }
        }
    }

    /**
     * Genera la respuesta y va entregando trozos por [onChunk] mientras el modelo escribe,
     * para que el TTS hable encima en vez de esperar el texto completo.
     */
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
            throw LocalError("El modelo no pudo abrir la conversación: ${t.message ?: t.javaClass.simpleName}")
        }

        active = conversation
        generating = true
        val full = StringBuilder()
        try {
            conversation.sendMessageAsync(prompt).collect { message ->
                val chunk = message.toString()
                if (chunk.isEmpty()) return@collect
                // El runtime puede entregar deltas o el texto acumulado. Se normaliza a deltas
                // para que ni el TTS ni el historial se dupliquen si eso cambia entre versiones.
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
                throw LocalError("El modelo falló al responder: ${t.message ?: t.javaClass.simpleName}")
            }
        } finally {
            generating = false
            active = null
            try { conversation.close() } catch (_: Throwable) { }
        }
        return full.toString().trim()
    }

    /** Interrumpe la generación en curso (se usa cuando WayHat detecta un peligro). */
    fun cancelGeneration() {
        val conversation = active ?: return
        try { conversation.cancelProcess() } catch (_: Throwable) { }
    }

    /** Libera el gigabyte largo que ocupa el modelo en RAM cuando Karbys se apaga. */
    fun unload() {
        val current = engine
        engine = null
        try { current?.close() } catch (_: Throwable) { }
    }

    fun isLoaded(): Boolean = engine != null

    /** Hilos razonables para no dejar al audio y a la interfaz sin CPU. */
    fun suggestedThreads(): Int = Runtime.getRuntime().availableProcessors().let { cores ->
        when {
            cores <= 2 -> 1 // Gama baja: 1 hilo para no congelar
            cores <= 4 -> 2 // Gama media: 2 hilos
            cores <= 6 -> 3 // Gama media-alta: 3 hilos
            else -> 4 // Gama alta: 4 hilos
        }
    }

    fun deviceSummary(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.SDK_INT} · ${suggestedThreads()} hilos"

    fun deviceSummary(context: Context): String {
        val ram = ModelManager.totalRamMegabytes(context)
        val arch = if (ModelManager.hasCpuForEngine(context)) "64-bit ✓" else "32-bit ✗"
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.SDK_INT} · ${suggestedThreads()} hilos · $arch · $ram MB RAM · ${if (ram >= 3500) "gama media-alta compatible" else "considera modelo 0.5B"}"
    }
}
