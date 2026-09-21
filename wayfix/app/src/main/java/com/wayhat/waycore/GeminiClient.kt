package com.wayhat.waycore

import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente de Gemini (nube) con autoselección del modelo.
 *
 * Google da de baja modelos viejos con frecuencia; por eso, en vez de un
 * nombre fijo, se consulta models.list y se elige el mejor disponible.
 * El resultado detallado (modelo usado / error técnico) se muestra en el
 * registro de la app para que siempre se sepa qué pasó.
 */
data class GeminiResult(
    val text: String,
    val model: String?,
    val error: String?
)

object GeminiClient {

    /** Último recurso si models.list falla. Se prueban en orden. */
    private val STATIC_FALLBACKS = listOf(
        "gemini-3.8-flash",
        "gemini-3.1-pro-preview",
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash"
    )

    private const val KEY_CACHED_MODEL = "cached_model"
    private const val KEY_LIST_JSON = "model_list_json"
    private const val KEY_LIST_AT = "model_list_at"
    private const val LIST_TTL_MS = 24L * 60L * 60L * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private sealed interface GenOut {
        data class Ok(val json: JSONObject) : GenOut
        data class Http(val code: Int, val snippet: String) : GenOut
        data class Net(val message: String) : GenOut
    }

    /** Compatible con el código anterior. */
    suspend fun ask(user: String, memory: List<ConversationTurn>, deviceContext: String): String =
        askDetailed(user, memory, deviceContext, null).text

    suspend fun askDetailed(
        user: String,
        memory: List<ConversationTurn>,
        deviceContext: String,
        prefs: SharedPreferences?
    ): GeminiResult {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isBlank()) {
            return GeminiResult(
                "Falta configurar la clave de Gemini en WayCore. Mientras tanto puedo ayudarte con la IA local sin internet.",
                null, "sin_clave"
            )
        }
        if (user.isBlank()) return GeminiResult("No escuché ninguna pregunta.", null, "vacia")

        return try {
            val base = JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts", JSONArray().put(JSONObject().put("text", buildPrompt(user, memory, deviceContext)))
                )
            )

            val candidates = resolveCandidates(apiKey, prefs)
            var lastDetail = "sin modelos candidatos"
            for (model in candidates) {
                // Copia fresca del contenido para cada intento.
                val contents = JSONArray(base.toString())
                when (val g = generate(apiKey, model, contents)) {
                    is GenOut.Ok -> {
                        saveCachedModel(prefs, model)
                        val finalText = runToolLoop(apiKey, model, g.json, contents)
                        return GeminiResult(finalText, model, null)
                    }
                    is GenOut.Http -> {
                        lastDetail = "HTTP ${g.code} en $model ${g.snippet}".trim()
                        when {
                            g.code == 404 || looksLikeUnknownModel(g.snippet) -> continue // probar siguiente modelo
                            g.code == 401 || g.code == 403 || looksLikeBadKey(g.snippet) ->
                                return GeminiResult(
                                    "La clave de Gemini no es válida o no tiene permiso. Revisa la clave configurada en WayCore.",
                                    model, "clave_invalida"
                                )
                            g.code == 429 ->
                                return GeminiResult(
                                    "Gemini está saturado o se acabó la cuota gratis. Inténtalo en unos minutos o usa la IA local.",
                                    model, "cuota"
                                )
                            else ->
                                return GeminiResult(
                                    "Gemini devolvió un error (código ${g.code}). Inténtalo de nuevo o usa la IA local.",
                                    model, "http_${g.code}"
                                )
                        }
                    }
                    is GenOut.Net ->
                        return GeminiResult(
                            "No pude conectar con Gemini. Revisa tu conexión a Internet.",
                            null, "red: ${g.message}"
                        )
                }
            }
            GeminiResult(
                "Ningún modelo de Gemini respondió en este momento. Puedo seguir ayudándote con la IA local sin internet.",
                null, lastDetail
            )
        } catch (e: Exception) {
            GeminiResult(
                "No pude conectar con Gemini. Revisa tu conexión a Internet.",
                null, "ex: ${e.message}"
            )
        }
    }

    private fun resolveCandidates(apiKey: String, prefs: SharedPreferences?): List<String> {
        val out = LinkedHashSet<String>()
        cachedModel(prefs)?.let { out.add(it) }
        try {
            cachedList(prefs)?.let { out.addAll(it) } ?: listModelsLive(apiKey).let { live ->
                if (live.isNotEmpty()) {
                    saveList(prefs, live)
                    out.addAll(live)
                }
            }
        } catch (_: Exception) { }
        out.addAll(STATIC_FALLBACKS)
        return out.take(8)
    }

    private fun cachedModel(prefs: SharedPreferences?): String? {
        val m = prefs?.getString(KEY_CACHED_MODEL, null)?.trim().orEmpty()
        return m.ifBlank { null }
    }

    private fun saveCachedModel(prefs: SharedPreferences?, model: String) {
        try { prefs?.edit()?.putString(KEY_CACHED_MODEL, model)?.apply() } catch (_: Exception) { }
    }

    private fun cachedList(prefs: SharedPreferences?): List<String>? {
        if (prefs == null) return null
        val at = prefs.getLong(KEY_LIST_AT, 0L)
        if (System.currentTimeMillis() - at > LIST_TTL_MS) return null
        val raw = prefs.getString(KEY_LIST_JSON, null) ?: return null
        return try {
            val a = JSONArray(raw)
            buildList { for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
                .ifEmpty { null }
        } catch (_: Exception) { null }
    }

    private fun saveList(prefs: SharedPreferences?, models: List<String>) {
        if (prefs == null) return
        try {
            val a = JSONArray()
            models.forEach { a.put(it) }
            prefs.edit().putString(KEY_LIST_JSON, a.toString())
                .putLong(KEY_LIST_AT, System.currentTimeMillis()).apply()
        } catch (_: Exception) { }
    }

    /** Pregunta a la API qué modelos existen y elige los aptos para generar texto. */
    private fun listModelsLive(apiKey: String): List<String> {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
            .addHeader("x-goog-api-key", apiKey)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) return emptyList()
            val models = JSONObject(raw).optJSONArray("models") ?: return emptyList()
            val ids = mutableListOf<String>()
            for (i in 0 until models.length()) {
                val o = models.optJSONObject(i) ?: continue
                val methods = o.optJSONArray("supportedGenerationMethods") ?: continue
                var supports = false
                for (j in 0 until methods.length()) {
                    if (methods.optString(j) == "generateContent") { supports = true; break }
                }
                if (!supports) continue
                val name = o.optString("name").removePrefix("models/").trim()
                if (name.isBlank()) continue
                val low = name.lowercase()
                if (low.contains("embedding") || low.contains("imagen") || low.contains("tts") ||
                    low.contains("live") || low.contains("aqa") || low.contains("computer-use") ||
                    low.contains("robotics") || low.contains("image")
                ) continue
                ids.add(name)
            }
            // Preferencia: flash-lite (rápido/barato) > flash > pro > resto.
            fun rank(n: String): Int {
                val l = n.lowercase()
                return when {
                    l.contains("flash-lite") || l.contains("flash_lite") -> 0
                    l.contains("flash") -> 1
                    l.contains("pro") -> 2
                    else -> 3
                }
            }
            return ids.sortedBy(::rank).distinct().take(6)
        }
    }

    private fun looksLikeUnknownModel(snippet: String): Boolean {
        val s = snippet.lowercase()
        return s.contains("not found") || s.contains("not supported") || s.contains("unknown model") ||
            s.contains("not available") || s.contains("deprecated") || s.contains("shutdown") ||
            s.contains("invalid model") || s.contains("model name") || s.contains("not recognized")
    }

    private fun looksLikeBadKey(snippet: String): Boolean {
        val s = snippet.lowercase()
        return s.contains("api key") || s.contains("api_key") || s.contains("permission denied") ||
            s.contains("unauthenticated") || s.contains("invalid api") || s.contains("api key not valid")
    }

    /** Bucle de function calling (máx. 3 rondas) con el modelo ya elegido. */
    private suspend fun runToolLoop(apiKey: String, model: String, first: JSONObject, contents: JSONArray): String {
        var raw: JSONObject? = first
        repeat(3) {
            val candidate = raw?.optJSONArray("candidates")?.optJSONObject(0)
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

            contents.put(JSONObject(modelContent.toString()).put("role", "model"))
            val responseParts = JSONArray()
            for (call in calls) {
                val name = call.optString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                val result = WayHatService.executeTool(name, args)
                responseParts.put(
                    JSONObject().put(
                        "functionResponse", JSONObject()
                            .put("name", name)
                            .put("response", JSONObject().put("result", result))
                    )
                )
            }
            contents.put(JSONObject().put("role", "user").put("parts", responseParts))
            raw = when (val g = generate(apiKey, model, contents)) {
                is GenOut.Ok -> g.json
                else -> return text.ifBlank { "No pude terminar la acción de WayHat en este momento." }
            }
        }
        return "No pude terminar la acción de WayHat en este momento."
    }

    private fun generate(apiKey: String, model: String, contents: JSONArray): GenOut {
        return try {
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

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val snippet = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                    } catch (_: Exception) { raw }
                    return GenOut.Http(response.code, snippet.take(220))
                }
                GenOut.Ok(JSONObject(raw))
            }
        } catch (e: Exception) {
            GenOut.Net(e.message ?: "red")
        }
    }

    private fun toolDeclarations(): JSONArray {
        fun fn(name: String, description: String, properties: JSONObject = JSONObject(), required: JSONArray = JSONArray()): JSONObject {
            val params = JSONObject().put("type", "object").put("properties", properties)
            if (required.length() > 0) params.put("required", required)
            return JSONObject().put("name", name).put("description", description).put("parameters", params)
        }

        return JSONArray()
            .put(
                fn(
                    "set_wayhat_sensitivity",
                    "Cambia el alcance de seguridad de los avisos de proximidad de WayHat. Úsala cuando el usuario pida que WayHat avise antes o después. El valor permitido es de 20 a 150 centímetros.",
                    JSONObject().put("centimeters", JSONObject().put("type", "integer").put("description", "Distancia de activación en centímetros, entre 20 y 150.")),
                    JSONArray().put("centimeters")
                )
            )
            .put(
                fn(
                    "set_wayhat_mode",
                    "Cambia el modo de WayHat. SAFE mantiene los avisos de proximidad activos; CHAT silencia los avisos de proximidad para facilitar una conversación, pero mantiene la telemetría.",
                    JSONObject().put("mode", JSONObject().put("type", "string").put("enum", JSONArray().put("SAFE").put("CHAT"))),
                    JSONArray().put("mode")
                )
            )
            .put(
                fn(
                    "set_wayhat_alerts",
                    "Activa o desactiva los avisos sonoros de proximidad del WayHat. Solo controla el buzzer de seguridad; no modifica los sonidos de Karbys.",
                    JSONObject().put("enabled", JSONObject().put("type", "boolean").put("description", "true para activar los avisos, false para desactivarlos.")),
                    JSONArray().put("enabled")
                )
            )
            .put(
                fn(
                    "test_wayhat_alert",
                    "Hace un pitido corto de prueba en WayHat cuando el usuario lo solicita explícitamente."
                )
            )
            .put(
                fn(
                    "refresh_wayhat_telemetry",
                    "Solicita al ESP32 una lectura inmediata de sus sensores antes de responder cuando el usuario pide datos actuales o cuando la última lectura disponible parece antigua."
                )
            )
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
