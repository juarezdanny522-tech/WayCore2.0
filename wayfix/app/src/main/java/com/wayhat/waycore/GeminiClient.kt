package com.wayhat.waycore

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente de Gemini con function calling para controlar WayHat.
 *
 * Dos detalles importantes:
 *  - La clave se lee de Prefs (ajustes en la app) y solo usa BuildConfig como respaldo,
 *    para poder repartir el mismo APK sin dejar una clave dentro del repositorio.
 *  - Si Google jubila un id de modelo, WayCore prueba los siguientes en la lista en vez
 *    de quedarse mudo.
 */
object GeminiClient {
    private const val RETRY_MODEL = "\u0000model"

    // MODELOS REALES que existen en 2026 y funcionan en gama media-alta.
    // Antes tenías 3.5 y 3.1 que no existen y daban "modelo no se puede usar".
    private val FALLBACK_MODELS = listOf(
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-1.5-flash-8b",
        "gemini-2.0-flash-lite",
        "gemini-2.5-flash"
    )

    val MODEL_SUGGESTIONS: List<String> get() = FALLBACK_MODELS

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun models(context: Context): List<String> =
        (listOf(Prefs.model(context)) + FALLBACK_MODELS).map { it.trim() }.filter { it.isNotBlank() }.distinct()

    suspend fun ask(context: Context, user: String, memory: List<ConversationTurn>, deviceContext: String): String {
        val apiKey = Prefs.apiKey(context)
        if (apiKey.isBlank()) return "Todavía falta tu clave de Gemini. Abre WayCore, escríbela en el campo Clave de Gemini y toca Guardar clave. Mientras tanto sí puedo hablar de WayHat, sensores, alarmas y recordatorios."
        if (user.isBlank()) return "No escuché ninguna pregunta."

        val candidates = models(context)
        var modelUnavailable = false
        for (model in candidates) {
            val answer = converse(model, apiKey, user, memory, deviceContext)
            if (answer == RETRY_MODEL) {
                modelUnavailable = true
            } else {
                return answer
            }
        }
        return if (modelUnavailable) {
            "Ninguno de los modelos que conoce WayCore funciona con tu clave. Los que prueba son ${candidates.joinToString(", ")}. Puedes escribir otro nombre de modelo en los ajustes."
        } else {
            "No pude obtener una respuesta válida de Gemini."
        }
    }

    private suspend fun converse(
        model: String,
        apiKey: String,
        user: String,
        memory: List<ConversationTurn>,
        deviceContext: String
    ): String {
        val contents = JSONArray().put(
            JSONObject().put("role", "user").put(
                "parts", JSONArray().put(JSONObject().put("text", buildPrompt(user, memory, deviceContext)))
            )
        )

        repeat(3) {
            val reply = generate(model, apiKey, contents)
            val error = reply.json?.optJSONObject("error")
            val status = error?.optString("status").orEmpty().uppercase()

            when {
                reply.code == 200 -> Unit
                reply.code == 0 -> return "No pude conectar con Gemini. Revisa tu conexión a Internet."
                reply.code == 401 || reply.code == 403 || status.contains("API_KEY") ->
                    return "La clave de Gemini no es válida o no tiene permiso para este modelo. Revísala en los ajustes de WayCore."
                reply.code == 429 || status.contains("RESOURCE_EXHAUSTED") ->
                    return "Gemini dice que vas pasado de peticiones por ahora. Espera un minuto y pregúntame de nuevo."
                reply.code == 404 || status.contains("NOT_FOUND") || status.contains("NOT_SUPPORTED") ->
                    return RETRY_MODEL
                else -> return "Gemini respondió un error ${reply.code}. Si sigue pasando, cambia el modelo en los ajustes de WayCore."
            }

            val raw = reply.json ?: return "Gemini devolvió una respuesta vacía."
            val blocked = raw.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            if (blocked.isNotBlank()) return "Gemini frenó esta pregunta por su regla de seguridad $blocked. Intenta formularla de otra forma."

            val candidate = raw.optJSONArray("candidates")?.optJSONObject(0)
                ?: return "Gemini no devolvió una respuesta válida."
            val modelContent = candidate.optJSONObject("content") ?: JSONObject()
            val parts = modelContent.optJSONArray("parts") ?: JSONArray()

            val calls = mutableListOf<JSONObject>()
            var text = ""
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                part.optJSONObject("functionCall")?.let { calls += it }
                val t = part.optString("text").trim()
                if (t.isNotBlank()) text = if (text.isBlank()) t else "$text\n$t"
            }

            if (calls.isEmpty()) return text.ifBlank { "No recibí una respuesta hablada de Gemini." }

            // Preserve Gemini's model content, including tool-call metadata/signatures.
            contents.put(JSONObject(modelContent.toString()).put("role", "model"))
            val responseParts = JSONArray()
            for (call in calls) {
                val name = call.optString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                val result = WayHatService.executeTool(name, args)
                responseParts.put(
                    JSONObject().put("functionResponse", JSONObject()
                        .put("name", name)
                        .put("response", JSONObject().put("result", result)))
                )
            }
            contents.put(JSONObject().put("role", "user").put("parts", responseParts))
        }

        return "No pude terminar la acción de WayHat en este momento."
    }

    private class Reply(val code: Int, val json: JSONObject?)

    private fun generate(model: String, apiKey: String, contents: JSONArray): Reply {
        val body = JSONObject()
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", toolDeclarations())))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val parsed = try { JSONObject(raw) } catch (_: Exception) { null }
                Reply(if (response.isSuccessful) 200 else response.code, parsed)
            }
        } catch (_: Exception) {
            Reply(0, null)
        }
    }

    private fun toolDeclarations(): JSONArray {
        fun fn(name: String, description: String, properties: JSONObject = JSONObject(), required: JSONArray = JSONArray()): JSONObject {
            val params = JSONObject().put("type", "object").put("properties", properties)
            if (required.length() > 0) params.put("required", required)
            return JSONObject().put("name", name).put("description", description).put("parameters", params)
        }

        return JSONArray()
            .put(fn(
                "set_wayhat_sensitivity",
                "Cambia el alcance de seguridad de los avisos de proximidad de WayHat. Úsala cuando el usuario pida que WayHat avise antes o después. El valor permitido es de 20 a 150 centímetros.",
                JSONObject().put("centimeters", JSONObject().put("type", "integer").put("description", "Distancia de activación en centímetros, entre 20 y 150.")),
                JSONArray().put("centimeters")
            ))
            .put(fn(
                "set_wayhat_mode",
                "Cambia el modo de WayHat. SAFE mantiene los avisos de proximidad activos; CHAT silencia los avisos de proximidad para facilitar una conversación, pero mantiene la telemetría.",
                JSONObject().put("mode", JSONObject().put("type", "string").put("enum", JSONArray().put("SAFE").put("CHAT"))),
                JSONArray().put("mode")
            ))
            .put(fn(
                "set_wayhat_alerts",
                "Activa o desactiva los avisos sonoros de proximidad del WayHat. Solo controla el buzzer de seguridad; no modifica los sonidos de Karbys.",
                JSONObject().put("enabled", JSONObject().put("type", "boolean").put("description", "true para activar los avisos, false para desactivarlos.")),
                JSONArray().put("enabled")
            ))
            .put(fn(
                "test_wayhat_alert",
                "Hace un pitido corto de prueba en WayHat cuando el usuario lo solicita explícitamente."
            ))
            .put(fn(
                "refresh_wayhat_telemetry",
                "Solicita al ESP32 una lectura inmediata de sus sensores antes de responder cuando el usuario pide datos actuales o cuando la última lectura disponible parece antigua."
            ))
    }

    private fun buildPrompt(user: String, memory: List<ConversationTurn>, deviceContext: String): String {
        val history = if (memory.isEmpty()) "No hay conversación anterior disponible." else memory.takeLast(4).joinToString("\n") {
            "Usuario: ${it.user}\nKarbys: ${it.assistant}"
        }

        return """
Eres Karbys, el asistente personal y cerebro digital de WayCore, pronunciado "guaycor". WayHat, pronunciado "guayjat", es el dispositivo físico que ayudas a controlar. Karbys es un producto de WayCorp y vive dentro de WayCore.

REGLA FUNDAMENTAL DE DATOS:
Los datos dentro de ESTADO ACTUAL DEL DISPOSITIVO son lecturas reales proporcionadas por el teléfono y WayHat. Son la fuente de verdad. Nunca inventes, completes, redondees de forma engañosa ni supongas valores de sensores. Si un valor aparece como null, -1, unavailable, false o como sensor desconectado/no disponible, dilo claramente y no adivines. Si el usuario pregunta por distancia, batería, ubicación, modo o sensibilidad, usa los datos actuales de este contexto.

ACCESIBILIDAD:
El proyecto está diseñado especialmente para personas con discapacidad visual. Reduce al mínimo la necesidad de interacción visual. Da respuestas claras, concretas y accionables. No infantilices ni hagas suposiciones sobre la persona. Prioriza seguridad y accesibilidad.

CONTROL AUTÓNOMO DE WAYHAT:
Tienes herramientas seguras para controlar WayHat. Puedes usarlas cuando el usuario lo pida o cuando sea claramente necesario para cumplir su intención. No puedes ejecutar código arbitrario ni enviar comandos Bluetooth arbitrarios. Solo existen las funciones declaradas.
- Puedes cambiar sensibilidad entre 20 y 150 cm.
- Puedes cambiar SAFE o CHAT.
- Puedes activar o desactivar los avisos sonoros de proximidad.
- Puedes hacer una prueba del buzzer si el usuario la solicita.
- Puedes solicitar una lectura actualizada de sensores.
Para cambios de seguridad solicitados claramente por el usuario, actúa directamente. Si una acción podría dejar a la persona menos protegida de forma ambigua, pregunta antes de realizarla. Nunca desactives seguridad por tu cuenta solo porque estés conversando.

ESTADO ACTUAL DEL DISPOSITIVO:
$deviceContext

PERSONALIDAD:
- Amable, cálida, servicial, paciente, natural y con un toque de humor.
- Español latinoamericano natural para El Salvador.
- Todo lo que digas está pensado para ser escuchado en voz alta.
- No uses Markdown, listas, emojis, símbolos raros ni respuestas innecesariamente largas.
- No te presentes como "Hola, soy Karbys" en cada respuesta.
- WayCore se pronuncia "guaycor" y WayHat "guayjat".
- No controles música todavía.

ORIGEN Y EQUIPO:
El proyecto fue creado con amor y cariño para todos. Danny Joel Castro Juárez es estudiante de primer año de Desarrollo de Software en el Instituto Nacional de San Miguel Tepezontes y lidera el desarrollo de software, WayCore e integración de Karbys. Dennis Alexander es desarrollador de hardware. Daylin Odalis es secretaria, portavoz, documentadora y responsable de verificación de procesos. Emely Denisse es diseñadora y documentadora. Danny expresa un agradecimiento especial a la licenciada Gloria Yessenia Mármol de Muñoz por su dedicación para enseñarles esta carrera técnica. Menciona estos datos SOLO cuando el usuario pregunte por el origen, equipo, historia, propósito o agradecimientos del proyecto.

MEMORIA CORTA:
$history

MENSAJE ACTUAL:
$user

Responde a la intención del usuario usando el estado real del dispositivo. Si necesitas controlar WayHat, usa las herramientas disponibles y luego explica brevemente qué hiciste.
        """.trimIndent()
    }
}

data class ConversationTurn(
    val user: String,
    val assistant: String
)
