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
        if (!file.isFile) throw LocalError("El modelo todavía no está descargado en el teléfono.")

        val wanted = if (Prefs.useGpu(context)) listOf(Backend.GPU(), Backend.CPU()) else listOf(Backend.CPU())
        var last: Throwable? = null
        for (backend in wanted) {
            try {
                val candidate = Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = backend,
                        cacheDir = context.cacheDir.path
                    )
                )
                candidate.initialize()
                return candidate
            } catch (t: Throwable) {
                last = t
            }
        }
        throw LocalError(
            "No se pudo cargar el modelo en este teléfono. " +
                (last?.message?.take(160) ?: "error desconocido")
        )
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
            cores <= 2 -> 2
            cores <= 5 -> 3
            else -> 4
        }
    }

    fun deviceSummary(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.SDK_INT} · ${suggestedThreads()} hilos"
}
