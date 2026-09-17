package com.wayhat.waycore

import org.json.JSONArray
import org.json.JSONObject

/** Una llamada a herramienta ya validada contra la lista blanca. */
data class ToolCall(val name: String, val args: JSONObject)

/**
 * Function calling para el modelo local, sin depender de la plantilla de tools del runtime.
 *
 * Un modelo de 1.5B en un teléfono no se puede tratar como un oráculo: se le pide UNA línea
 * con formato, y aquí se parsea, se normaliza (acepta "sensibilidad", "cm", "SEGURO", "80 cm",
 * comillas simples, JSON con texto alrededor) y se descarta todo lo que no pase la lista blanca
 * con los mismos límites que ya usa el camino de Gemini. Si el modelo escribe basura, la
 * consecuencia es "no lo entendí", nunca una orden equivocada en un dispositivo que guía a una
 * persona ciega.
 */
object ToolProtocol {
    const val MAX_ROUNDS = 3
    private val ACTION_MARKERS = listOf("ACCIÓN:", "ACCION:", "ACCIÓN :", "TOOL:", "LLAMAR:")

    val TOOL_NAMES = listOf(
        "set_wayhat_sensitivity",
        "set_wayhat_mode",
        "set_wayhat_alerts",
        "test_wayhat_alert",
        "refresh_wayhat_telemetry"
    )

    /** Instrucciones para el modelo. Cortas a propósito: cada token aquí es prefill en el teléfono. */
    fun systemPrompt(persona: String): String = """
$persona

PUEDES CONTROLAR EL SOMBRERO WAYHAT.
Si la persona pide cambiar algo del sombrero, contesta SOLO una línea con este formato:
ACCIÓN: {"name":"set_wayhat_sensitivity","args":{"centimeters":80}}
Herramientas válidas:
set_wayhat_sensitivity args {"centimeters": entero de 20 a 150}
set_wayhat_mode args {"mode":"SAFE" o "CHAT"}
set_wayhat_alerts args {"enabled": true o false}
test_wayhat_alert args {}
refresh_wayhat_telemetry args {}
Si no hace falta cambiar nada, empieza con DECIR: y responde en dos frases como mucho.
Un mensaje puede traer resultados de acciones ya ejecutadas: en ese caso resume lo hecho en una frase.
Nunca inventes distancias ni datos: si algo dice sin lectura, di que no lo tienes.
""".trim()

    /** Esquema en JSON, usado por la prueba unitaria y por quien quiera mostrar las herramientas en pantalla. */
    fun schemaJson(): JSONArray = JSONArray()
        .put(JSONObject().put("name", "set_wayhat_sensitivity")
            .put("args", JSONObject().put("centimeters", "integer 20..150")))
        .put(JSONObject().put("name", "set_wayhat_mode")
            .put("args", JSONObject().put("mode", "SAFE|CHAT")))
        .put(JSONObject().put("name", "set_wayhat_alerts")
            .put("args", JSONObject().put("enabled", "boolean")))
        .put(JSONObject().put("name", "test_wayhat_alert").put("args", JSONObject()))
        .put(JSONObject().put("name", "refresh_wayhat_telemetry").put("args", JSONObject()))

    /**
     * Separa lo que se debe hablar de las llamadas a herramientas.
     * @return texto hablado (sin marcadores ni JSON) y las llamadas ya normalizadas.
     */
    fun extract(raw: String): Pair<String, List<ToolCall>> {
        val cleaned = raw.replace("```json", "").replace("```", "").replace("\r", "")
        val calls = mutableListOf<ToolCall>()
        val kept = StringBuilder()
        var cursor = 0
        for (range in objectRanges(cleaned)) {
            val snippet = cleaned.substring(range.first, range.last + 1)
            val call = parseCall(snippet) ?: continue
            calls += call
            kept.append(cleaned.substring(cursor, range.first))
            cursor = range.last + 1
        }
        kept.append(cleaned.substring(cursor))

        val spoken = kept.toString()
            .split("\n")
            .map { speakableLine(it) }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return spoken to calls
    }

    /**
     * Deja solo la parte hablable de una línea. Un JSON que el modelo no supo escribir, o una
     * herramienta que no está en la lista blanca, se tira a la basura en vez de leerse en voz
     * alta: para una persona ciega escuchar llaves y comillas no es información.
     */
    private fun speakableLine(line: String): String {
        var text = line.trim()
        text = text.replace(Regex("(?i)\\b(ACCI[OÓ]N|TOOL|LLAMAR)\\s*:\\s*"), "")
        text = text.replace(Regex("(?i)\\b(DECIR|HABLAR|RESPUESTA)\\s*:\\s*"), "")
        val brace = text.indexOf('{')
        if (brace >= 0) text = text.substring(0, brace)
        // Fragmentos de JSON escrito en varias líneas ("name": "x",) tampoco son habla.
        if (Regex("^\"?[A-Za-z_][A-Za-z0-9_]*\"?\\s*:.*[,}]?$").matches(text.trim())) text = ""
        return text.trim()
    }

    /** Marcadores de rango de cada objeto `{...}` balanceado, respetando cadenas y escapes. */
    fun objectRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        ranges += start..i
                        start = -1
                    }
                }
            }
        }
        return ranges
    }

    private fun parseCall(snippet: String): ToolCall? = try {
        val obj = JSONObject(snippet)
        val name = obj.optString("name")
            .ifBlank { obj.optString("tool") }
            .ifBlank { obj.optString("function") }
            .ifBlank { obj.optString("nombre") }
        val canonical = canonical(name)
        if (canonical.isBlank()) null
        else {
            val args = obj.optJSONObject("arguments")
                ?: obj.optJSONObject("args")
                ?: obj.optJSONObject("parametros")
                ?: JSONObject()
            coerce(canonical, args)?.let { ToolCall(it.first, it.second) }
        }
    } catch (_: Exception) {
        null
    }

    /** Acepta nombres sueltos: "sensibilidad", "set_mode", "cambiar_modo"… */
    fun canonical(name: String): String {
        val n = name.lowercase().filter { it.isLetterOrDigit() || it == '_' }
        if (n.isBlank()) return ""
        return when {
            n.contains("sensib") || n.contains("sensit") || n.contains("umbral") || n.contains("alcance") -> "set_wayhat_sensitivity"
            n.contains("modo") || n.contains("mode") -> "set_wayhat_mode"
            n.contains("prueba") || n.contains("test") || n.contains("bipe") -> "test_wayhat_alert"
            n.contains("aviso") || n.contains("alert") || n.contains("sonido") || n.contains("buzzer") ||
                n.contains("silenc") || n.contains("activ") -> "set_wayhat_alerts"
            n.contains("refresc") || n.contains("actualiz") || n.contains("sensores") ||
                n.contains("telemetr") || n.contains("leer") -> "refresh_wayhat_telemetry"
            else -> ""
        }
    }

    /**
     * Valida y normaliza los argumentos. Devuelve null si la llamada no se puede ejecutar:
     * los límites 20..150 cm y SAFE/CHAT son exactamente los mismos que exige WayHatService.
     */
    fun coerce(name: String, args: JSONObject): Pair<String, JSONObject>? {
        val out = JSONObject()
        when (name) {
            "set_wayhat_sensitivity" -> {
                val cm = numberFrom(args, "centimeters", "centimetros", "cm", "distance", "distancia", "value")
                    ?: return null
                if (cm !in 20..150) return null
                out.put("centimeters", cm)
            }
            "set_wayhat_mode" -> {
                val raw = stringFrom(args, "mode", "modo", "value")?.trim()?.uppercase() ?: return null
                val mapped = when {
                    raw == "SAFE" || raw.startsWith("SEGU") || raw == "SEGUR" -> "SAFE"
                    raw.startsWith("CHAT") || raw.startsWith("CHARL") || raw == "CONVERSACION" -> "CHAT"
                    else -> return null
                }
                out.put("mode", mapped)
            }
            "set_wayhat_alerts" -> {
                val enabled = boolFrom(args, "enabled", "value", "activos", "avisos", "buzzer") ?: return null
                out.put("enabled", enabled)
            }
            "test_wayhat_alert", "refresh_wayhat_telemetry" -> Unit
            else -> return null
        }
        return name to out
    }

    private fun stringFrom(args: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = args.opt(key)
            when (value) {
                null, JSONObject.NULL -> Unit
                is String -> if (value.isNotBlank()) return value
                is Number, is Boolean -> return value.toString()
            }
        }
        return null
    }

    private fun numberFrom(args: JSONObject, vararg keys: String): Int? {
        val text = stringFrom(args, *keys) ?: return null
        val digits = Regex("\\d+").find(text)?.value ?: return null
        return digits.toIntOrNull()
    }

    private fun boolFrom(args: JSONObject, vararg keys: String): Boolean? {
        for (key in keys) {
            val value = args.opt(key)
            when (value) {
                null, JSONObject.NULL -> Unit
                is Boolean -> return value
                is Number -> return value.toInt() != 0
                is String -> {
                    val v = value.trim().lowercase()
                    if (v in setOf("true", "1", "si", "sí", "on", "activar", "activado", "activa", "verdadero")) return true
                    if (v in setOf("false", "0", "no", "off", "desactivar", "desactivado", "silencio", "falso")) return false
                }
            }
        }
        return null
    }

    /**
     * Solo se ejecutan cambios si la persona los pidió con un verbo de cambio. Un modelo de
     * 1.5B alucina herramientas; que eso se traduzca en que el sombrero cambie de modo mientras
     * alguien cruza la calle es inaceptable. Las lecturas sí se permiten: no alteran nada.
     */
    private val CHANGE_PATTERNS = listOf(
        Regex("(?i)\\b(cambi|pon\\w*|sub\\w*|baj\\w*|ajust|configur|fij\\w*|activ|desactiv|apag|prend|enciend|silenci|prueb|prob\\w*|revis|lee|le\\s+(el|la|los)|actualiz|refresc|necesit|quiero|prefiero|deja|qu\\w*ta)"),
        Regex("(?i)\\b(al|en|a)\\s+modo\\b"),
        Regex("(?i)modo\\s+(seguro|charla|chat)")
    )

    /** Herramientas que no cambian el estado del sombrero y por eso se pueden ejecutar siempre. */
    fun readOnly(name: String): Boolean = name == "refresh_wayhat_telemetry"

    fun userAsksForChange(text: String): Boolean = CHANGE_PATTERNS.any { it.containsMatchIn(text) }

    /** La primera línea ya delata si el modelo quiere usar una herramienta. */
    fun looksLikeToolLine(text: String): Boolean {
        val head = text.trimStart()
        if (ACTION_MARKERS.any { head.uppercase().startsWith(it) }) return true
        if (head.startsWith("{") || head.startsWith("[")) return true
        if (head.startsWith("DECIR") || head.startsWith("HABLAR") || head.startsWith("RESPUESTA")) return false
        return objectRanges(head).isNotEmpty()
    }

    /** Prompt de la segunda vuelta: se le devuelven al modelo los resultados reales del hardware. */
    fun followUp(userText: String, executed: String, freshState: String): String = """
RESULTADOS DE LAS ACCIONES EJECUTADAS EN EL SOMBRERO:
$executed

DATOS REALES AHORA:
$freshState

PETICIÓN ORIGINAL DE LA PERSONA:
$userText

CONFIRMA EN UNA FRASE CORTA LO QUE CAMBIÓ, con los números de los RESULTADOS. Empieza con DECIR:
""".trim()

    /** Texto corto y hablable para la interfaz y para el registro. */
    fun describe(call: ToolCall): String = when (call.name) {
        "set_wayhat_sensitivity" -> "sensibilidad a ${call.args.optInt("centimeters")} centímetros"
        "set_wayhat_mode" -> "modo ${if (call.args.optString("mode") == "SAFE") "seguro" else "charla"}"
        "set_wayhat_alerts" -> if (call.args.optBoolean("enabled")) "avisos sonoros encendidos" else "avisos sonoros apagados"
        "test_wayhat_alert" -> "prueba del zumbador"
        "refresh_wayhat_telemetry" -> "lectura nueva de los sensores"
        else -> call.name
    }
}
