package com.wayhat.waycore

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cerebro local de Karbys: Qwen2.5-1.5B-Instruct (8-bit, 4096 de contexto)
 * corriendo DENTRO del teléfono con LiteRT-LM de Google. No se necesita PC,
 * Ollama ni Internet: el modelo se descarga desde la propia app.
 *
 * El function calling lo gestiona la librería: cuando el modelo decide
 * llamar a una herramienta, se ejecuta la función anotada con @Tool de
 * [WayHatTools] y el resultado se devuelve al modelo automáticamente hasta
 * que este responde con el texto final.
 *
 * Seguridad: todas las herramientas delegan en WayHatService.executeTool,
 * que valida cada comando contra su lista blanca y espera la confirmación
 * del ESP32. El modelo no puede ejecutar nada que no esté permitido.
 */
object LocalQwen {
    private const val NO_MODEL_MESSAGE =
        "El modelo local todavía no está cargado. Abre la pantalla principal y pulsa Cargar modelo."

    private var engine: Engine? = null

    val isLoaded: Boolean
        get() = engine != null

    /**
     * Carga el modelo en memoria. Primero intenta GPU (más rápido) y si el
     * teléfono no lo soporta cae a CPU con 4 hilos (XNNPACK).
     */
    suspend fun load(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        unloadInternal()
        val backends = listOf(Backend.GPU(), Backend.CPU(threadCount = 4))
        var lastError: Throwable? = null
        for (backend in backends) {
            val eng = try {
                // maxNumTokens = tamaño del KV-cache (el modelo trae 4096).
                Engine(EngineConfig(modelPath = modelPath, backend = backend, maxNumTokens = 4096))
            } catch (e: Throwable) {
                lastError = e
                continue
            }
            try {
                eng.initialize()
                engine = eng
                return@withContext Result.success(Unit)
            } catch (e: Throwable) {
                lastError = e
                try { eng.close() } catch (_: Exception) {}
            }
        }
        Result.failure(IllegalStateException("No pude cargar el modelo local: ${lastError?.message}"))
    }

    suspend fun unload(): Unit = withContext(Dispatchers.IO) {
        unloadInternal()
        Unit
    }

    private fun unloadInternal() {
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }

    /**
     * Pregunta a Karbys con contexto fresco del dispositivo. Cada pregunta
     * usa una conversación nueva (el contexto de 4096 tokens se limita a
     * prompt + memoria corta + respuesta), igual que el flujo anterior.
     */
    suspend fun ask(user: String, memory: List<ConversationTurn>, deviceContext: String): String {
        val eng = engine ?: return NO_MODEL_MESSAGE
        if (user.isBlank()) return "No escuché ninguna pregunta."

        return try {
            withContext(Dispatchers.IO) {
                val conversation = eng.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(buildSystemPrompt(memory, deviceContext)),
                        tools = listOf(tool(WayHatTools())),
                        samplerConfig = SamplerConfig(topK = 20, topP = 0.8, temperature = 0.7)
                    )
                )
                try {
                    var answer = ""
                    conversation.sendMessageAsync(user).collect { message ->
                        answer += message.text()
                    }
                    answer.trim().ifBlank { "No recibí una respuesta del modelo." }
                } finally {
                    try { conversation.close() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            "El modelo local no pudo responder en este momento. Inténtalo de nuevo en unos segundos."
        }
    }

    private fun Message.text(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /**
     * Prompt compacto para un modelo de 1.5B. Las herramientas ya no se
     * describen aquí: la librería las inyecta en el formato que el modelo
     * conoce (plantilla de Qwen2.5).
     */
    private fun buildSystemPrompt(memory: List<ConversationTurn>, deviceContext: String): String {
        val history = if (memory.isEmpty()) {
            "No hay conversación anterior disponible."
        } else {
            memory.takeLast(4).joinToString("\n") { "Usuario: ${it.user}\nKarbys: ${it.assistant}" }
        }

        return """
Eres Karbys, el asistente de voz del sistema WayCore (se pronuncia "guaycor"). Ayudas a controlar WayHat (se pronuncia "guayjat"), el dispositivo físico con sensores de distancia que acompaña a su usuario.

REGLA DE DATOS:
- La sección ESTADO ACTUAL DEL DISPOSITIVO contiene lecturas reales del teléfono y de WayHat. Es la única fuente de verdad.
- Nunca inventes, completes ni adivines valores de sensores. Si un valor es null, -1, unavailable o un sensor está desconectado, dilo con claridad.

HERRAMIENTAS:
- Cuando el usuario pida un cambio en WayHat, usa la herramienta correspondiente y luego explica en una o dos frases breves qué hiciste.
- Nunca desactives seguridad por tu cuenta. Si un cambio podría dejar a la persona menos protegida de forma ambigua, pregunta antes.

ESTILO:
- Español latinoamericano natural (El Salvador). Habla como en una conversación telefónica.
- Respuestas cortas, claras y accionables. Sin Markdown, listas, emojis ni símbolos raros.
- Amable, paciente, servicial, con un toque de humor. No te presentes como "soy Karbys" en cada respuesta.
- Tu usuario puede tener discapacidad visual: prioriza seguridad y claridad, sin infantilizar.
- No controles música todavía.

ORIGEN (solo si el usuario pregunta por el proyecto, el equipo o los agradecimientos):
WayCore es un proyecto de WayCorp creado por estudiantes del Instituto Nacional de San Miguel Tepezontes. Danny Joel Castro Juárez lidera el software y a Karbys; Dennis Alexander es hardware; Daylin Odalis es secretaría, portavoz y verificación; Emely Denisse es diseño y documentación. Agradecimiento especial a la licenciada Gloria Yessenia Mármol de Muñoz.

MEMORIA CORTA:
$history

ESTADO ACTUAL DEL DISPOSITIVO:
$deviceContext
        """.trimIndent()
    }
}

/**
 * Las 5 herramientas de WayHat expuestas al modelo. LiteRT-LM convierte
 * los nombres camelCase a snake_case (setWayhatSensitivity ->
 * set_wayhat_sensitivity), que son los mismos nombres que usaba la app
 * antes, y todas delegan en la validación de WayHatService.
 */
class WayHatTools : ToolSet {
    // La librería ejecuta estas funciones por reflexión (no son suspend),
    // así que bloqueamos el hilo del motor con runBlocking mientras la
    // herramienta espera la confirmación del ESP32.

    @Tool(description = "Cambia el alcance de los avisos de proximidad de WayHat. Úsala cuando el usuario pida que WayHat avise antes o después. El valor permitido es de 20 a 150 centímetros.")
    fun setWayhatSensitivity(
        @ToolParam(description = "Distancia de activación en centímetros, entre 20 y 150.") centimeters: Int
    ): String = runBlocking {
        WayHatService.executeTool("set_wayhat_sensitivity", JSONObject().put("centimeters", centimeters))
    }

    @Tool(description = "Cambia el modo de WayHat. SAFE mantiene los avisos de proximidad activos; CHAT silencia los avisos para conversar, pero mantiene la telemetría.")
    fun setWayhatMode(
        @ToolParam(description = "El modo a activar: SAFE o CHAT.") mode: String
    ): String = runBlocking {
        WayHatService.executeTool("set_wayhat_mode", JSONObject().put("mode", mode))
    }

    @Tool(description = "Activa o desactiva los avisos sonoros de proximidad de WayHat. Solo controla el buzzer de seguridad; no modifica los sonidos de Karbys.")
    fun setWayhatAlerts(
        @ToolParam(description = "true para activar los avisos, false para desactivarlos.") enabled: Boolean
    ): String = runBlocking {
        WayHatService.executeTool("set_wayhat_alerts", JSONObject().put("enabled", enabled))
    }

    @Tool(description = "Hace un pitido corto de prueba en WayHat cuando el usuario lo solicita.")
    fun testWayhatAlert(): String = runBlocking {
        WayHatService.executeTool("test_wayhat_alert", JSONObject())
    }

    @Tool(description = "Solicita al ESP32 una lectura inmediata de sus sensores antes de responder cuando el usuario pide datos actuales.")
    fun refreshWayhatTelemetry(): String = runBlocking {
        WayHatService.executeTool("refresh_wayhat_telemetry", JSONObject())
    }
}

data class ConversationTurn(
    val user: String,
    val assistant: String
)
