package com.wayhat.waycore

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {
    // Modelos compatibles con gama media-alta, ordenados de mejor a más compatible.
    // gemini-3.1 no existe, por eso fallaba con "modelo no se puede usar".
    private val MODELS_TO_TRY = listOf(
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-1.5-flash-8b",
        "gemini-2.0-flash-lite"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun ask(user: String, memory: List<ConversationTurn>, deviceContext: String): String {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isBlank()) return "Falta configurar la clave de Gemini en WayCore. Ve a local.properties y pon GEMINI_API_KEY."
        if (user.isBlank()) return "No escuché ninguna pregunta."

        return try {
            val contents = JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts", JSONArray().put(JSONObject().put("text", buildPrompt(user, memory, deviceContext)))
                )
            )

            // Intentamos hasta 3 rondas de function calling, con fallback de modelos
            repeat(3) { round ->
                var lastError: String? = null
                var raw: JSONObject? = null

                for (model in MODELS_TO_TRY) {
                    val result = generateWithModel(apiKey, model, contents)
                    if (result.isSuccess) {
                        raw = result.json
                        break
                    } else {
                        lastError = result.error
                        // Si es error 404 de modelo no encontrado, probamos siguiente modelo inmediatamente
                        if (result.shouldTryNextModel) continue else break
                    }
                }

                if (raw == null) {
                    return lastError ?: "No pude obtener una respuesta válida de Gemini. Revisa internet."
                }

                val candidate = raw.optJSONArray("candidates")?.optJSONObject(0)
                    ?: return "Gemini no devolvió una respuesta válida, pero el modelo sí funciona. Intenta de nuevo."

                // Si hay finishReason SAFETY o similar, dar mensaje claro
                val finishReason = candidate.optString("finishReason", "")
                if (finishReason == "SAFETY") {
                    return "No puedo responder eso por seguridad, pero dime de otra forma y te ayudo."
                }

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

                if (calls.isEmpty()) {
                    // Si no hay function calls, retornamos texto directo
                    return text.ifBlank {
                        // A veces Gemini devuelve texto vacío con finishReason MAX_TOKENS, etc.
                        if (finishReason.isNotBlank()) "Recibí una respuesta vacía de Gemini con motivo $finishReason. Intenta preguntar de nuevo más corto."
                        else "No recibí una respuesta hablada de Gemini, pero el modelo ya funciona en tu dispositivo."
                    }
                }

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

            "No pude terminar la acción de WayHat en este momento, pero el modelo ya es compatible con tu celular."
        } catch (e: Exception) {
            "No pude conectar con Gemini: ${e.message}. Revisa tu conexión a Internet. El modelo ahora es compatible con gama media."
        }
    }

    private data class GenerateResult(
        val json: JSONObject?,
        val error: String?,
        val shouldTryNextModel: Boolean
    ) {
        val isSuccess get() = json != null
    }

    private fun generateWithModel(apiKey: String, model: String, contents: JSONArray): GenerateResult {
        val body = JSONObject()
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", toolDeclarations())))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.7)
                .put("maxOutputTokens", 1024)
            )
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
                if (!response.isSuccessful) {
                    // Analizar error para decidir si probar siguiente modelo
                    val isModelNotFound = response.code == 404 || raw.contains("not found", ignoreCase = true) || raw.contains("is not supported", ignoreCase = true) || raw.contains("model", ignoreCase = true) && raw.contains("not", ignoreCase = true)
                    val errorMsg = when (response.code) {
                        404 -> "Modelo $model no encontrado, probando otro compatible..."
                        400 -> "Error 400 con $model: $raw"
                        403 -> "Clave API no válida o sin permisos."
                        429 -> "Límite de uso de Gemini alcanzado, espera un minuto."
                        500, 503 -> "Servidor de Gemini ocupado, reintentando..."
                        else -> "Error ${response.code}: $raw"
                    }
                    // Para 404, 400 con modelo no soportado, y 5xx, intentamos siguiente modelo
                    val shouldTryNext = response.code == 404 || response.code == 500 || response.code == 503 || (response.code == 400 && raw.contains("model", true))
                    return GenerateResult(null, errorMsg, shouldTryNext)
                }
                return GenerateResult(JSONObject(raw), null, false)
            }
        } catch (e: Exception) {
            GenerateResult(null, "Error de red con $model: ${e.message}", true)
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
