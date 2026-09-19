package com.wayhat.waycore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del protocolo de function calling local.
 *
 * Importantes de verdad: aquí es donde un modelo de 1.5B puede colar una orden mal
 * interpretada en un sombrero que guía a una persona ciega. Se prueba el formato estricto,
 * los alias, las unidades sucias ("80 centímetros"), el recorte de límites y, sobre todo,
 * que una acción inválida NUNCA se convierta en una orden.
 */
class ToolProtocolTest {

    private fun spoken(raw: String) = ToolProtocol.extract(raw).first
    private fun calls(raw: String) = ToolProtocol.extract(raw).second

    @Test fun `respuesta normal se queda sin marcadores y sin llamadas`() {
        val result = ToolProtocol.extract("DECIR: Hola, te escucho bien.")
        assertEquals("Hola, te escucho bien.", result.first)
        assertTrue(result.second.isEmpty())
    }

    @Test fun `una accion limpia se ejecuta y no se habla`() {
        val raw = """ACCIÓN: {"name":"set_wayhat_sensitivity","args":{"centimeters":80}}"""
        val result = ToolProtocol.extract(raw)
        assertEquals(1, result.second.size)
        assertEquals("set_wayhat_sensitivity", result.second[0].name)
        assertEquals(80, result.second[0].args.optInt("centimeters"))
        assertEquals("", result.first)
    }

    @Test fun `prosa antes de la accion se habla y la accion se ejecuta`() {
        val raw = """Voy a cambiarlo. ACCIÓN: {"name":"set_wayhat_mode","args":{"mode":"CHAT"}}"""
        val result = ToolProtocol.extract(raw)
        assertEquals("Voy a cambiarlo.", result.first)
        assertEquals("set_wayhat_mode", result.second.single().name)
        assertEquals("CHAT", result.second.single().args.optString("mode"))
    }

    @Test fun `alias y unidades sucias se normalizan`() {
        val raw = """ACCIÓN: {"nombre":"sensibilidad","parametros":{"cm":"80 centímetros"}}"""
        val call = calls(raw).single()
        assertEquals("set_wayhat_sensitivity", call.name)
        assertEquals(80, call.args.optInt("centimeters"))
    }

    @Test fun `modo en español se mapea a SAFE`() {
        val call = calls("""ACCIÓN: {"tool":"set_wayhat_mode","arguments":{"modo":"SEGURO"}}""").single()
        assertEquals("SAFE", call.args.optString("mode"))
    }

    @Test fun `booleanos escritos como texto se entienden`() {
        val off = calls("""ACCIÓN: {"name":"set_wayhat_alerts","args":{"enabled":"false"}}""").single()
        assertFalse(off.args.optBoolean("enabled"))
        val on = calls("""ACCIÓN: {"name":"set_wayhat_alerts","args":{"avisos":"sí"}}""").single()
        assertTrue(on.args.optBoolean("enabled"))
    }

    @Test fun `fuera de los limites de seguridad no se ejecuta nada`() {
        // 200 cm está fuera del rango 20..150 que acepta el ESP32.
        assertTrue(calls("""ACCIÓN: {"name":"set_wayhat_sensitivity","args":{"centimeters":200}}""").isEmpty())
        assertTrue(calls("""ACCIÓN: {"name":"set_wayhat_sensitivity","args":{"centimeters":5}}""").isEmpty())
        // Los extremos sí pasan, y con el valor intacto.
        assertEquals(
            20,
            calls("""ACCIÓN: {"name":"set_wayhat_sensitivity","args":{"centimeters":20}}""").single().args.optInt("centimeters")
        )
        assertEquals(
            150,
            calls("""ACCIÓN: {"name":"set_wayhat_sensitivity","args":{"centimeters":150}}""").single().args.optInt("centimeters")
        )
    }

    @Test fun `herramienta inexistente se descarta y no rompe la respuesta`() {
        val raw = """ACCIÓN: {"name":"apagar_telefono","args":{}}"""
        assertTrue(calls(raw).isEmpty())
        // La línea inservible no debe salir hablada con llaves y comillas.
        assertEquals("", spoken(raw))
    }

    @Test fun `un json a medias no produce nada y no se habla`() {
        for (raw in listOf("ACCIÓN: {", "ACCIÓN: {\"name\":", "ACCIÓN: {}")) {
            assertTrue("debería ignorarse: $raw", calls(raw).isEmpty())
            assertEquals("no debe hablarse: $raw", "", spoken(raw))
        }
    }

    @Test fun `la tolerancia de org json recupera la intencion sin saltarse los limites`() {
        // Descubrimiento de la prueba en la JVM: org.json (la misma clase del framework de
        // Android) es tolerante y acepta claves y valores sin comillas. Se deja a propósito:
        // la seguridad no depende de que el JSON sea perfecto, depende de la lista blanca y de
        // que los centímetros estén entre 20 y 150. Un valor fuera de rango igual se descarta.
        val result = ToolProtocol.extract("""ACCIÓN: {"name": "set_wayhat_mode", args:{mode:CHAT}}""")
        assertEquals("set_wayhat_mode", result.second.single().name)
        assertEquals("CHAT", result.second.single().args.optString("mode"))
        assertEquals("", result.first)
        assertTrue(
            calls("""ACCIÓN: {"name": "set_wayhat_sensitivity", args:{centimeters:999}}""").isEmpty()
        )
    }

    @Test fun `varias acciones en un mismo mensaje se ejecutan en orden`() {
        val raw = """
            ACCIÓN: {"name":"set_wayhat_mode","args":{"mode":"SAFE"}}
            ACCIÓN: {"name":"test_wayhat_alert","args":{}}
        """.trimIndent()
        val names = calls(raw).map { it.name }
        assertEquals(listOf("set_wayhat_mode", "test_wayhat_alert"), names)
        assertEquals("", spoken(raw))
    }

    @Test fun `llaves dentro de cadenas no rompen el escaneo de objetos`() {
        val raw = """ACCIÓN: {"name":"set_wayhat_mode","args":{"mode":"CHAT"}} y listo"""
        val ranges = ToolProtocol.objectRanges(raw)
        assertEquals(1, ranges.size)
        val snippet = raw.substring(ranges[0].first, ranges[0].last + 1)
        assertEquals("CHAT", JSONObject(snippet).getJSONObject("args").getString("mode"))
    }

    @Test fun `las comillas escapadas no cierran la cadena`() {
        val text = """{"name":"set_wayhat_mode","args":{"mode":"SAFE \" raro"}}"""
        val ranges = ToolProtocol.objectRanges(text)
        assertEquals(1, ranges.size)
        assertEquals(text.length, ranges[0].last + 1)
    }

    @Test fun `detector de primera linea distingue orden de respuesta`() {
        assertTrue(ToolProtocol.looksLikeToolLine("ACCIÓN: {\"name\":"))
        assertTrue(ToolProtocol.looksLikeToolLine("""{"name":"set_wayhat_mode"""))
        assertFalse(ToolProtocol.looksLikeToolLine("DECIR: Hola, cómo estás"))
        assertFalse(ToolProtocol.looksLikeToolLine("Claro, lo reviso"))
    }

    @Test fun `el prompt del sistema menciona las cinco herramientas`() {
        val prompt = ToolProtocol.systemPrompt("Eres Karbys.")
        for (name in ToolProtocol.TOOL_NAMES) assertTrue("falta $name", prompt.contains(name))
        assertTrue(prompt.contains("ACCIÓN:"))
        assertTrue(prompt.contains("DECIR:"))
    }

    @Test fun `el seguimiento devuelve los resultados y el estado fresco`() {
        val prompt = ToolProtocol.followUp(
            "baja la sensibilidad a 60",
            "sensibilidad a 60 centímetros -> hecho, sensibilidad 60 cm",
            "sensores frente sin lectura; bateria 80%"
        )
        assertTrue(prompt.contains("sensibilidad 60 cm"))
        assertTrue(prompt.contains("bateria 80%"))
        assertTrue(prompt.endsWith("Empieza con DECIR:"))
    }

    @Test fun `describe se puede hablar sin JSON`() {
        val call = calls("""ACCIÓN: {"name":"set_wayhat_sensitivity","args":{"centimeters":95}}""").single()
        assertEquals("sensibilidad a 95 centímetros", ToolProtocol.describe(call))
    }

    @Test fun `un cambio que la persona no pidio no se ejecuta`() {
        assertTrue(ToolProtocol.userAsksForChange("baja la sensibilidad a sesenta"))
        assertTrue(ToolProtocol.userAsksForChange("cambia a modo seguro"))
        assertTrue(ToolProtocol.userAsksForChange("apaga los avisos"))
        assertTrue(ToolProtocol.userAsksForChange("prueba el zumbador"))
        assertFalse(ToolProtocol.userAsksForChange("¿a qué distancia está el obstáculo?"))
        assertFalse(ToolProtocol.userAsksForChange("hola, quién eres"))
        assertTrue(ToolProtocol.readOnly("refresh_wayhat_telemetry"))
        assertFalse(ToolProtocol.readOnly("set_wayhat_mode"))
    }

    @Test fun `el JSON suelto sin marcador se interpreta igual`() {
        val raw = """{"name":"refresh_wayhat_telemetry","args":{}}"""
        assertEquals("refresh_wayhat_telemetry", calls(raw).single().name)
    }

    @Test fun `json repartido en varias lineas se consume y no se habla`() {
        val raw = """
            Perfecto. ACCIÓN:
            {
              "name": "set_wayhat_mode",
              "args": {
                "mode": "SAFE"
              }
            }
            espera ahí
        """.trimIndent()
        val result = ToolProtocol.extract(raw)
        assertEquals("Perfecto. espera ahí", result.first)
        assertEquals("SAFE", result.second.single().args.optString("mode"))
    }

    @Test fun `numero con decimales y unidad se toma como centimetros`() {
        val call = calls("""ACCIÓN: {"name":"set_wayhat_sensitivity","args":{"centimeters":"80.5 cm"}}""").single()
        assertEquals(80, call.args.optInt("centimeters"))
    }

    @Test fun `nombre en mayusculas o con espacios se normaliza`() {
        val call = calls("""ACCIÓN: {"name":"Set Wayhat Mode","args":{"mode":"chat"}}""").single()
        assertEquals("set_wayhat_mode", call.name)
        assertEquals("CHAT", call.args.optString("mode"))
    }

    @Test fun `texto suelto del modelo se habla tal cual`() {
        assertEquals("No sé qué responder a eso", spoken("No sé qué responder a eso"))
        assertEquals("Voy a avisarte. Cruza con cuidado.", spoken("DECIR: Voy a avisarte. Cruza con cuidado."))
    }
}
