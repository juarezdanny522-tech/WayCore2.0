#!/usr/bin/env python3
"""
Prueba de function calling para Karbys con Qwen2.5-1.5B en Ollama.

Reproduce EXACTAMENTE lo que hace la app (QwenClient.kt):
mismo system prompt, mismas herramientas, mismo formato OpenAI-compatible,
mismo bucle de herramientas y mismo parser de respaldo (JSON en texto).

Uso (en el equipo donde corre Ollama):
    python3 test_qwen_ollama.py                     # http://127.0.0.1:11434
    python3 test_qwen_ollama.py --host http://192.168.1.50:11434

Solo usa la biblioteca estándar (no requiere pip install de nada).
"""

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request

MODEL = "qwen2.5:1.5b"

DEVICE_CONTEXT = """
Hora local del teléfono: 2026-09-17 09:30:00
Batería del teléfono: 74%
Ubicación del teléfono: lat=13.6614, lon=-88.1779, accuracy_m=12, age_ms=45000
WayHat telemetría JSON (fuente de verdad): {"type":"telemetry","available":true,"right":62,"left":135,"rear":-1,"tf":88,"closest":62,"threshold":50,"mode":"SAFE","buzzer":true}
Regla de seguridad: el TF-Luna tiene una zona de protección de mayor alcance que los HC-SR04.
""".strip()

SYSTEM_PROMPT = """Eres Karbys, el asistente de voz del sistema WayCore (se pronuncia "guaycor"). Ayudas a controlar WayHat (se pronuncia "guayjat"), el dispositivo físico con sensores de distancia que acompaña a su usuario.

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
%s
""" % DEVICE_CONTEXT

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "set_wayhat_sensitivity",
            "description": "Cambia el alcance de los avisos de proximidad de WayHat. Úsala cuando el usuario pida que WayHat avise antes o después. El valor permitido es de 20 a 150 centímetros.",
            "parameters": {
                "type": "object",
                "properties": {
                    "centimeters": {
                        "type": "integer",
                        "description": "Distancia de activación en centímetros, entre 20 y 150.",
                    }
                },
                "required": ["centimeters"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "set_wayhat_mode",
            "description": "Cambia el modo de WayHat. SAFE mantiene los avisos de proximidad activos; CHAT silencia los avisos para conversar, pero mantiene la telemetría.",
            "parameters": {
                "type": "object",
                "properties": {
                    "mode": {"type": "string", "enum": ["SAFE", "CHAT"]}
                },
                "required": ["mode"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "set_wayhat_alerts",
            "description": "Activa o desactiva los avisos sonoros de proximidad de WayHat. Solo controla el buzzer de seguridad; no modifica los sonidos de Karbys.",
            "parameters": {
                "type": "object",
                "properties": {
                    "enabled": {"type": "boolean", "description": "true para activar, false para desactivar."}
                },
                "required": ["enabled"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "test_wayhat_alert",
            "description": "Hace un pitido corto de prueba en WayHat cuando el usuario lo solicita.",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "refresh_wayhat_telemetry",
            "description": "Solicita al ESP32 una lectura inmediata de sus sensores antes de responder cuando el usuario pide datos actuales.",
            "parameters": {"type": "object", "properties": {}},
        },
    },
]

# Resultados simulados que la app (WayHatService + ESP32) devuelve al modelo.
TOOL_RESULTS = {
    "set_wayhat_sensitivity": "Listo. WayHat confirmó: sensibilidad a {centimeters} centímetros.",
    "set_wayhat_mode": "Listo. WayHat confirmó: modo {mode}.",
    "set_wayhat_alerts": "Listo. WayHat confirmó: avisos sonoros {estado}.",
    "test_wayhat_alert": "Listo. WayHat sonó el pitido de prueba.",
    "refresh_wayhat_telemetry": "Lectura actualizada: right=58, left=132, rear=-1, tf=91, closest=58.",
}


def post(base_url: str, path: str, payload: dict | None = None, timeout: float = 180.0):
    data = json.dumps(payload).encode("utf-8") if payload is not None else b""
    req = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_json(base_url: str, path: str, timeout: float = 10.0):
    req = urllib.request.Request(base_url.rstrip("/") + path)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def parse_tool_call_from_text(text: str):
    """Mismo parser de respaldo que QwenClient.parseToolCallFromText."""
    for start, ch in enumerate(text):
        if ch != "{":
            continue
        depth = 0
        for end in range(start, len(text)):
            if text[end] == "{":
                depth += 1
            elif text[end] == "}":
                depth -= 1
                if depth == 0:
                    snippet = text[start : end + 1]
                    try:
                        obj = json.loads(snippet)
                    except Exception:
                        obj = None
                    if isinstance(obj, dict) and obj.get("name") and any(
                        k in obj for k in ("arguments", "args", "parameters")
                    ):
                        if isinstance(obj.get("arguments"), str):
                            try:
                                obj["arguments"] = json.loads(obj["arguments"] or "{}")
                            except Exception:
                                obj["arguments"] = {}
                        return obj
                    break


def extract_tool_calls(message: dict, content: str):
    """Misma lógica que QwenClient.extractToolCalls."""
    calls = []
    for call in message.get("tool_calls") or []:
        fn = call.get("function") or call
        if fn.get("name"):
            merged = dict(fn)
            merged.setdefault("id", call.get("id", ""))
            calls.append(merged)
    if not calls and content.strip():
        parsed = parse_tool_call_from_text(content)
        if parsed:
            calls.append(parsed)
    return calls


def arguments_of(call: dict) -> dict:
    args = call.get("arguments")
    if isinstance(args, str):
        try:
            args = json.loads(args or "{}")
        except Exception:
            args = {}
    return args or {}


def run_ask(base_url: str, user: str, memory: list[dict]) -> tuple[str, list[dict], float]:
    """Ejecuta el mismo bucle de tool-calling que la app. Devuelve (texto, llamadas, segundos)."""
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages.extend(memory)
    messages.append({"role": "user", "content": user})

    started = time.time()
    last_text = ""
    all_calls = []
    for _ in range(3):
        body = {
            "model": MODEL,
            "messages": messages,
            "tools": TOOLS,
            "stream": False,
            "temperature": 0.7,
            "top_p": 0.8,
            "max_tokens": 512,
        }
        data = post(base_url, "/v1/chat/completions", body)
        message = (data.get("choices") or [{}])[0].get("message") or {}
        last_text = (message.get("content") or "").strip()

        calls = extract_tool_calls(message, last_text)
        if not calls:
            break
        all_calls.extend(calls)
        messages.append({"role": "assistant", "content": message.get("content") or "", **{k: v for k, v in message.items() if k != "content"}})
        for call in calls:
            name = call.get("name", "")
            args = arguments_of(call)
            result = TOOL_RESULTS.get(name, "No ejecutado: esa función de WayHat no está permitida.")
            if "estado" in result:
                result = result.format(estado="activados" if args.get("enabled") else "desactivados")
            else:
                try:
                    result = result.format(**args)
                except Exception:
                    pass
            messages.append(
                {
                    "role": "tool",
                    "tool_call_id": call.get("id") or f"call_{len(messages)}",
                    "name": name,
                    "content": result,
                }
            )
    return last_text, all_calls, time.time() - started


def check(desc: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {desc}" + (f"  -> {detail}" if detail and not ok else ""))
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="http://127.0.0.1:11434", help="URL base de Ollama (sin /v1)")
    ap.add_argument("--skip-model-check", action="store_true")
    args = ap.parse_args()

    base = args.host
    print(f"== Prueba Karbys x Qwen2.5-1.5B ==\nServidor: {base}\n")

    # 1) Servidor vivo.
    try:
        ver = get_json(base, "/api/version")
        print(f"Ollama responde (versión {ver.get('version', '?')}).")
    except Exception as e:
        print(f"[FAIL] No pude conectar con Ollama en {base}: {e}")
        print("¿Ollama está encendido? Si el modelo corre en OTRO equipo, usa --host http://IP:11434")
        sys.exit(1)

    # 2) Modelo disponible.
    if not args.skip_model_check:
        try:
            tags = get_json(base, "/api/tags")
            names = [m.get("name", "") for m in tags.get("models", [])]
            if not any(n == MODEL or n.startswith(MODEL + ":") for n in names):
                print(f"[FAIL] El modelo '{MODEL}' no está en Ollama. Modelos encontrados: {names or 'ninguno'}")
                print("Descárgalo con:  ollama pull qwen2.5:1.5b")
                sys.exit(1)
            print(f"Modelo '{MODEL}' disponible.")
        except Exception as e:
            print(f"[WARN] No pude listar modelos: {e}")

    print("\n--- Escenarios (los mismos que la app) ---")
    results = []

    # S1: sensibilidad
    text, calls, dt = run_ask(base, "Karbys, pon la sensibilidad a 80 centímetros", [])
    call = next((c for c in calls if c.get("name") == "set_wayhat_sensitivity"), None)
    ok = call is not None and int(arguments_of(call).get("centimeters", -1)) == 80
    results.append(check("sensibilidad a 80 cm", ok, f"llamadas={[(c.get('name'), arguments_of(c)) for c in calls]} texto={text[:80]!r}"))
    print(f"        (respuesta final: {text[:110]!r} | {dt:.1f}s)")

    # S2: modo charla
    text, calls, dt = run_ask(base, "Pon WayHat en modo charla", [])
    call = next((c for c in calls if c.get("name") == "set_wayhat_mode"), None)
    ok = call is not None and str(arguments_of(call).get("mode", "")).upper() == "CHAT"
    results.append(check("modo CHAT", ok, f"llamadas={[(c.get('name'), arguments_of(c)) for c in calls]} texto={text[:80]!r}"))
    print(f"        (respuesta final: {text[:110]!r} | {dt:.1f}s)")

    # S3: desactivar avisos
    text, calls, dt = run_ask(base, "Desactiva los avisos sonoros de WayHat", [])
    call = next((c for c in calls if c.get("name") == "set_wayhat_alerts"), None)
    ok = call is not None and arguments_of(call).get("enabled") is False
    results.append(check("desactivar avisos", ok, f"llamadas={[(c.get('name'), arguments_of(c)) for c in calls]} texto={text[:80]!r}"))
    print(f"        (respuesta final: {text[:110]!r} | {dt:.1f}s)")

    # S4: prueba del buzzer
    text, calls, dt = run_ask(base, "Prueba el buzzer de WayHat", [])
    call = next((c for c in calls if c.get("name") == "test_wayhat_alert"), None)
    ok = call is not None
    results.append(check("prueba del buzzer", ok, f"llamadas={[(c.get('name'), arguments_of(c)) for c in calls]} texto={text[:80]!r}"))
    print(f"        (respuesta final: {text[:110]!r} | {dt:.1f}s)")

    # S5: leer datos REALES del contexto (no debe inventar; rear=-1 debe decir que no está)
    text, calls, dt = run_ask(base, "¿A qué distancia está el obstáculo más cercano y está el sensor de atrás funcionando?", [])
    low = text.lower()
    ok = ("62" in text) and (
        "atrás" in low or "rearo" in low or "rear" in low or "no está" in low or "desconect" in low or "no disponible" in low or "no tengo" in low
    )
    results.append(check("lee telemetría real (62 cm + sensor atrás caído)", ok, f"llamadas={[(c.get('name'), arguments_of(c)) for c in calls]} texto={text[:120]!r}"))
    print(f"        (respuesta final: {text[:140]!r} | {dt:.1f}s)")

    # S6: conversación simple SIN herramienta
    text, calls, dt = run_ask(base, "Hola, ¿cómo estás?", [])
    ok = len(calls) == 0 and len(text) > 3
    results.append(check("conversación sin herramientas", ok, f"llamadas={[(c.get('name'), arguments_of(c)) for c in calls]} texto={text[:100]!r}"))
    print(f"        (respuesta final: {text[:120]!r} | {dt:.1f}s)")

    # S7: memoria (contexto de la conversación anterior)
    text, calls, dt = run_ask(
        base,
        "¿y la batería del celular?",
        [{"role": "user", "content": "¿Cómo va la cosa?"}, {"role": "assistant", "content": "Todo bien, aquí ando."}],
    )
    ok = len(calls) == 0 and "74" in text
    results.append(check("responde con datos del contexto (batería 74%)", ok, f"llamadas={[(c.get('name'), arguments_of(c)) for c in calls]} texto={text[:100]!r}"))
    print(f"        (respuesta final: {text[:120]!r} | {dt:.1f}s)")

    passed = sum(results)
    print(f"\n== Resultado: {passed}/{len(results)} escenarios pasan ==")
    if passed == len(results):
        print("VEREDICTO: SÍ sirve. Qwen2.5-1.5B controla WayHat bien con este setup.")
    elif passed >= len(results) - 1:
        print("VEREDICTO: Sirve con reservas. Revisa el escenario que falló arriba (a veces un comando suelto se equivoca).")
    else:
        print("VEREDICTO: Todavía no es confiable. Prueba: actualizar Ollama, o usar qwen2.5:3b que llama mejor a funciones.")
    sys.exit(0 if passed >= len(results) - 1 else 1)


if __name__ == "__main__":
    main()
