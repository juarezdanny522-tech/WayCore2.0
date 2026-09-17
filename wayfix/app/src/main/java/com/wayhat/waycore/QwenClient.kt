package com.wayhat.waycore

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente de Karbys para el modelo local Qwen2.5-1.5B.
 *
 * Habla el protocolo OpenAI-compatible de un servidor local
 * (Ollama por defecto, también compatible con LM Studio, llama.cpp y vLLM):
 *   POST {QWEN_BASE_URL}/chat/completions
 *
 * El function calling usa el formato estándar "tools". Como los modelos
 * pequeños a veces emiten la llamada de herramienta como JSON dentro del
 * texto en vez de en tool_calls, hay un parser de respaldo que detecta
 * ese patrón. Todos los argumentos se validan igualmente en WayHatService
 * (lista blanca), así que el modelo no puede hacer nada que no esté permitido.
 */
object QwenClient {
    private const val MODEL = "qwen2.5:1.5b"
    private const val MAX_TOOL_ROUNDS = 3
    private const val FALLBACK_ERROR =
        "No pude conectar con el modelo local. Revisa que el equipo con Ollama esté encendido y en la misma red Wi-Fi."

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun ask(user: String, memory: List<ConversationTurn>, deviceContext: String): String {
        val baseUrl = BuildConfig.QWEN_BASE_URL.trim().removeSurrounding("\"").removeSuffix("/")
        if (user.isBlank()) return "No escuché ninguna pregunta."
        if (baseUrl.isEmpty()) {
            return "Falta configurar QWEN_BASE_URL en local.properties. Ejemplo: http://192.168.1.50:11434/v1"
        }

        return try {
            val messages = JSONArray()
            messages.put(systemMessage(deviceContext))
            for (turn in memory.takeLast(4)) {
                if (turn.user.isNotBlank()) messages.put(JSONObject().put("role", "user").put("content", turn.user))
                if (turn.assistant.isNotBlank()) messages.put(JSONObject().put("role", "assistant").put("content", turn.assistant))
            }
            messages.put(JSONObject().put("role", "user").put("content", user))

            var lastText = ""
            repeat(MAX_TOOL_ROUNDS) {
                val message = chat(baseUrl, messages) ?: return FALLBACK_ERROR
                lastText = message.optString("content").trim()

                val calls = extractToolCalls(message, lastText)
                if (calls.isEmpty()) return lastText.ifBlank { "No recibí una respuesta del modelo." }

                // Guarda la respuesta del modelo (con sus tool_calls) y devuelve
                // el resultado de cada herramienta como mensajes de rol "tool".
                messages.put(JSONObject(message.toString()).put("role", "assistant"))
                for (call in calls) {
                    val name = call.optString("name")
                    val result = WayHatService.executeTool(name, call.arguments())
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.optString("id").ifBlank { "call_${messages.length()}" })
                            .put("name", name)
                            .put("content", result)
                    )
                }
            }
            "No pude terminar la acción de WayHat en este momento."
        } catch (_: Exception) {
            FALLBACK_ERROR
        }
    }

    private fun chat(baseUrl: String, messages: JSONArray): JSONObject? {
        // Ollama ignora campos desconocidos en el endpoint OpenAI-compatible
        // (su "options" solo existe en /api/chat), así que el request se queda
        // en formato OpenAI puro para ser portable con LM Studio, llama.cpp y vLLM.
        // El contexto por defecto de Ollama (4096 tokens) alcanza con holgura
        // para prompt + herramientas + memoria de Karbys.
        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("tools", toolDefinitions())
            .put("stream", false)
            .put("temperature", 0.7)
            .put("top_p", 0.8)
            .put("max_tokens", 512)

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) return null
            return try {
                val json = JSONObject(raw)
                json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Devuelve las llamadas de herramienta: primero tool_calls estándar, luego el JSON en texto. */
    private fun extractToolCalls(message: JSONObject, contentText: String): List<JSONObject> {
        val calls = mutableListOf<JSONObject>()
        message.optJSONArray("tool_calls")?.let { array ->
            for (i in 0 until array.length()) {
                val call = array.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: call
                if (fn.optString("name").isNotBlank()) {
                    calls += JSONObject(fn.toString()).put("id", call.optString("id"))
                }
            }
        }
        if (calls.isEmpty() && contentText.isNotBlank()) {
            parseToolCallFromText(contentText)?.let { calls += it }
        }
        return calls
    }

    /**
     * Respaldo para modelos pequeños que emiten la llamada como texto,
     * p. ej. {"name": "test_wayhat_alert", "arguments": {}}.
     * Escanea objetos JSON balanceados en el texto y acepta el primero
     * que tenga "name" y ("arguments" | "args" | "parameters").
     */
    private fun parseToolCallFromText(text: String): JSONObject? {
        for (start in text.indices) {
            if (text[start] != '{') continue
            var depth = 0
            for (end in start until text.length) {
                when (text[end]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val candidate = try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { null }
                            if (candidate != null &&
                                candidate.optString("name").isNotBlank() &&
                                (candidate.has("arguments") || candidate.has("args") || candidate.has("parameters"))) {
                                candidate.normalizeArguments()
                                return candidate
                            }
                        }
                        break
                    }
                }
            }
        }
        return null
    }

    private fun JSONObject.normalizeArguments() {
        var args = optJSONObject("arguments") ?: optJSONObject("args") ?: optJSONObject("parameters")
        if (args == null) {
            val raw = opt("arguments")
            if (raw is String) {
                val s = raw.trim()
                args = if (s.isEmpty()) JSONObject() else try { JSONObject(s) } catch (_: Exception) { JSONObject() }
            } else {
                args = JSONObject()
            }
        }
        put("arguments", args)
    }

    private fun JSONObject.arguments(): JSONObject {
        val args = optJSONObject("arguments") ?: optJSONObject("args")
        if (args != null) return args
        val raw = opt("arguments")
        return if (raw is String) {
            val s = raw.trim()
            if (s.isEmpty()) JSONObject() else try { JSONObject(s) } catch (_: Exception) { JSONObject() }
        } else {
            JSONObject()
        }
    }

    private fun systemMessage(deviceContext: String): JSONObject {
        return JSONObject().put("role", "system").put("content", """
Eres Karbys, el asistente de voz del sistema WayCore (se pronuncia "guaycor"). Ayudas a controlar WayHat (se pronuncia "guayjat"), el dispositivo físico con sensores de distancia que acompaña a su usuario.

REGLA DE DATOS:
- La sección ESTADO ACTUAL DEL DISPOSITIVO contiene lecturas reales del teléfono y de WayHat. Es la única fuente de verdad.
- Nunca inventes, completes ni adivines valores de sensores. Si un valor es null, -1, unavailable o un sensor está desconectado, dilo con claridad.

HERRAMIENTAS:
- Solo existen las funciones declaradas en "tools": sensibilidad de avisos (20 a 150 cm), modo SAFE/CHAT, activar o desactivar avisos sonoros, prueba del buzzer y refrescar telemetría.
- Cuando el usuario pida un cambio en WayHat, llama a la función correspondiente y luego explica en una o dos frases breves qué hiciste.
- Nunca desactives seguridad por tu cuenta. Si un cambio podría dejar a la persona menos protegida de forma ambigua, pregunta antes.

ESTILO:
- Español latinoamericano natural (El Salvador). Habla como en una conversación telefónica.
- Respuestas cortas, claras y accionables. Sin Markdown, listas, emojis ni símbolos raros.
- Amable, paciente, servicial, con un toque de humor. No te presentes como "soy Karbys" en cada respuesta.
- Tu usuario puede tener discapacidad visual: prioriza seguridad y claridad, sin infantilizar.
- No controles música todavía.

ORIGEN (solo si el usuario pregunta por el proyecto, el equipo o los agradecimientos):
WayCore es un proyecto de WayCorp creado por estudiantes del Instituto Nacional de San Miguel Tepezontes. Danny Joel Castro Juárez lidera el software y a Karbys; Dennis Alexander es hardware; Daylin Odalis es secretaría, portavoz y verificación; Emely Denisse es diseño y documentación. Agradecimiento especial a la licenciada Gloria Yessenia Mármol de Muñoz.

ESTADO ACTUAL DEL DISPOSITIVO:
$deviceContext
        """.trimIndent())
    }

    private fun toolDefinitions(): JSONArray {
        fun fn(
            name: String,
            description: String,
            properties: JSONObject = JSONObject(),
            required: JSONArray = JSONArray()
        ): JSONObject {
            val params = JSONObject().put("type", "object").put("properties", properties)
            if (required.length() > 0) params.put("required", required)
            return JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", params))
        }

        return JSONArray()
            .put(fn(
                "set_wayhat_sensitivity",
                "Cambia el alcance de los avisos de proximidad de WayHat. Úsala cuando el usuario pida que WayHat avise antes o después. El valor permitido es de 20 a 150 centímetros.",
                JSONObject().put("centimeters", JSONObject().put("type", "integer").put("description", "Distancia de activación en centímetros, entre 20 y 150.")),
                JSONArray().put("centimeters")
            ))
            .put(fn(
                "set_wayhat_mode",
                "Cambia el modo de WayHat. SAFE mantiene los avisos de proximidad activos; CHAT silencia los avisos para conversar, pero mantiene la telemetría.",
                JSONObject().put("mode", JSONObject().put("type", "string").put("enum", JSONArray().put("SAFE").put("CHAT"))),
                JSONArray().put("mode")
            ))
            .put(fn(
                "set_wayhat_alerts",
                "Activa o desactiva los avisos sonoros de proximidad de WayHat. Solo controla el buzzer de seguridad; no modifica los sonidos de Karbys.",
                JSONObject().put("enabled", JSONObject().put("type", "boolean").put("description", "true para activar, false para desactivar.")),
                JSONArray().put("enabled")
            ))
            .put(fn("test_wayhat_alert", "Hace un pitido corto de prueba en WayHat cuando el usuario lo solicita."))
            .put(fn("refresh_wayhat_telemetry", "Solicita al ESP32 una lectura inmediata de sus sensores antes de responder cuando el usuario pide datos actuales."))
    }
}

data class ConversationTurn(
    val user: String,
    val assistant: String
)
