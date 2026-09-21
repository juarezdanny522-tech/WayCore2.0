package com.wayhat.waycore

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Motor de IA 100% local (on-device) basado en MediaPipe LLM Inference.
 *
 * - Funciona SIN internet una vez descargado el modelo .task.
 * - Una sola instancia del motor, con acceso serializado (Mutex).
 * - Una sesión nueva por pregunta (barata y evita arrastrar estado corrupto).
 */
data class LocalAnswer(val ok: Boolean, val text: String)

object LocalBrain {

    sealed interface State {
        data object NoModel : State
        data object Loading : State
        data object Ready : State
        data class Error(val message: String) : State
    }

    private const val MAX_TOKENS = 1024
    private const val TOP_K = 40
    private const val TEMPERATURE = 0.7f
    private const val ANSWER_TIMEOUT_MS = 150_000L
    private const val LOAD_TIMEOUT_MS = 120_000L
    private const val MAX_REPLY_CHARS = 800

    private val mutex = Mutex()

    @Volatile private var engine: LlmInference? = null
    @Volatile private var state: State = State.NoModel
    @Volatile var lastError: String? = null
        private set

    fun status(ctx: Context): State {
        if (engine != null) return State.Ready
        if (state is State.Error) return state
        return if (LocalModelManager.isModelReady(ctx)) state else State.NoModel
    }

    fun statusText(ctx: Context): String = when (val s = status(ctx)) {
        is State.Ready -> "IA local lista (sin internet)."
        is State.Loading -> "Cargando modelo en memoria…"
        is State.NoModel -> "Sin modelo. " + LocalModelManager.modelStatusText(ctx)
        is State.Error -> "Error de IA local: ${s.message}"
    }

    /** Carga el motor si hace falta. Devuelve true si quedó listo. */
    suspend fun ensureLoaded(ctx: Context): Boolean = mutex.withLock {
        ensureLoadedLocked(ctx)
    }

    private suspend fun ensureLoadedLocked(ctx: Context): Boolean {
        engine?.let {
            state = State.Ready
            return true
        }
        val file = LocalModelManager.anyModelFile(ctx)
        if (file == null) {
            state = State.NoModel
            lastError = "No hay modelo .task instalado."
            return false
        }
        if (!LocalModelManager.looksLikeTaskBundle(file)) {
            state = State.Error("El archivo ${file.name} está corrupto o incompleto. Bórralo y descarga de nuevo.")
            lastError = (state as State.Error).message
            return false
        }
        state = State.Loading
        return try {
            withContext(Dispatchers.Default) {
                withTimeout(LOAD_TIMEOUT_MS) {
                    // En tasks-genai 0.10.27 el muestreo (topK/temperatura) solo existe
                    // en las opciones de sesión; aquí solo van modelo y tokens.
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(file.absolutePath)
                        .setMaxTokens(MAX_TOKENS)
                        .build()
                    engine = LlmInference.createFromOptions(ctx.applicationContext, options)
                }
            }
            state = State.Ready
            lastError = null
            true
        } catch (e: UnsatisfiedLinkError) {
            fail("Este celular no es compatible con el motor de IA (se requiere ARM de 64 bits).")
            false
        } catch (e: OutOfMemoryError) {
            fail("Sin memoria RAM para cargar el modelo. Cierra otras apps e inténtalo de nuevo.")
            false
        } catch (e: TimeoutCancellationException) {
            fail("Cargar el modelo tardó demasiado. Inténtalo de nuevo.")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("No se pudo cargar el modelo (${e.message ?: "error desconocido"}).")
            false
        }
    }

    private fun fail(message: String) {
        try { engine?.close() } catch (_: Exception) { }
        engine = null
        state = State.Error(message)
        lastError = message
    }

    /** Genera una respuesta local. Siempre devuelve algo (ok=false con motivo si falla). */
    suspend fun answer(ctx: Context, prompt: String): LocalAnswer = mutex.withLock {
        if (prompt.isBlank()) return LocalAnswer(false, "Pregunta vacía.")
        if (!ensureLoadedLocked(ctx)) {
            return LocalAnswer(false, lastError ?: "Modelo no disponible.")
        }
        val llm = engine ?: return LocalAnswer(false, lastError ?: "Motor no disponible.")
        try {
            val raw = withContext(Dispatchers.Default) {
                withTimeout(ANSWER_TIMEOUT_MS) {
                    val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(TOP_K)
                        .setTemperature(TEMPERATURE)
                        .build()
                    val session = LlmInferenceSession.createFromOptions(llm, sessionOptions)
                    try {
                        session.addQueryChunk(prompt)
                        session.generateResponse()
                    } finally {
                        try { session.close() } catch (_: Exception) { }
                    }
                }
            }
            val clean = cleanReply(raw)
            if (clean.isBlank()) {
                LocalAnswer(false, "El modelo devolvió una respuesta vacía. Inténtalo de nuevo.")
            } else {
                LocalAnswer(true, clean)
            }
        } catch (e: UnsatisfiedLinkError) {
            fail("Este celular no es compatible con el motor de IA (se requiere ARM de 64 bits).")
            LocalAnswer(false, lastError!!)
        } catch (e: OutOfMemoryError) {
            fail("Sin memoria durante la respuesta. Cierra otras apps e inténtalo de nuevo.")
            LocalAnswer(false, lastError!!)
        } catch (e: TimeoutCancellationException) {
            LocalAnswer(false, "La IA local tardó demasiado en responder. Inténtalo de nuevo.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LocalAnswer(false, "La IA local no respondió (${e.message ?: "error"}).")
        }
    }

    /** Libera la RAM del motor. Se vuelve a cargar solo cuando se necesite. */
    suspend fun release() = mutex.withLock {
        try { engine?.close() } catch (_: Exception) { }
        engine = null
        if (state !is State.Error) state = State.NoModel
    }

    /**
     * Limpia la salida cruda de un modelo pequeño: recorta ecos del prompt,
     * turnos inventados y respuestas eternas (todo se habla en voz alta).
     */
    private fun cleanReply(raw: String): String {
        var t = raw.trim()
        // Corta si el modelo empieza a inventar turnos nuevos.
        for (marker in listOf("\nUsuario:", "\nUSER:", "\nKarbys:", "\n<start_of_turn>", "<end_of_turn>")) {
            val i = t.indexOf(marker)
            if (i > 0) t = t.substring(0, i).trim()
        }
        t = t.replace("<start_of_turn>", "").replace("<end_of_turn>", "").trim()
        if (t.length > MAX_REPLY_CHARS) {
            val cut = t.substring(0, MAX_REPLY_CHARS)
            val lastEnd = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf(".\n"), cut.lastIndexOf("? "), cut.lastIndexOf("! "))
            t = if (lastEnd > MAX_REPLY_CHARS / 2) cut.substring(0, lastEnd + 1).trim() else "$cut…"
        }
        return t
    }
}
