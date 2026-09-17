# WayCore + WayHat v0.6.0 — Karbys con Qwen2.5 local (function calling)

Karbys ya no usa la API de Gemini. El cerebro del asistente es **Qwen2.5-1.5B**
corriendo en un equipo local de la casa/escuela, servido con **Ollama**.

¿Qué cambia?

- Sin API key ni Internet para pensar: Karbys "piensa" en la red local.
  (La app sigue usando la red para otras cosas; el Bluetooth sigue siendo independiente.)
- El function calling se hace con el protocolo estándar OpenAI-compatible
  (`/v1/chat/completions` + `tools`), así que si mañana prefieren LM Studio,
  llama.cpp o vLLM, solo cambia `QWEN_BASE_URL`.
- Si el servidor local no está encendido, Karbys lo dice con claridad en voz
  alta y no rompe nada.
- Todos los comandos de hardware siguen en lista blanca y con confirmación
  desde el ESP32 (`WayHatService.executeTool`): aunque el modelo se equivoque
  con los argumentos, solo se ejecutan valores válidos.

## Requisitos

| Rol | Equipo |
| --- | --- |
| Servidor del modelo | Un PC/tablet en la **misma red Wi-Fi** que el teléfono. Mínimo 8 GB de RAM (CPU). Con GPU va mucho más rápido. |
| Teléfono | Android 8.0+ con Bluetooth Classic y WayHat vinculados. |

## 1. Instalar Ollama en el equipo del modelo

- **Windows:** bajar el instalador de `ollama.com` y ejecutarlo.
- **Linux:** `curl -fsSL https://ollama.com/install.sh | sh`
- **macOS:** instalador oficial o `brew install ollama`

Descargar el modelo (≈1 GB, se descarga una sola vez):

```bash
ollama pull qwen2.5:1.5b
```

## 2. Permitir conexiones desde el teléfono

Ollama solo escucha en el propio equipo por defecto. Hay que abrirlo a la red:

- **Windows:** en el panel de administración de Ollama, o creando una variable
  de entorno `OLLAMA_HOST=0.0.0.0` y reiniciando Ollama. Asegúrate de que el
  firewall permita conexiones entrantes en el puerto **11434**.
- **Linux:** edita `/etc/systemd/system/ollama.service` y agrega
  `Environment="OLLAMA_HOST=0.0.0.0"`, luego
  `sudo systemctl daemon-reload && sudo systemctl restart ollama`.
- **macOS:** `OLLAMA_HOST=0.0.0.0 ollama serve` (o en `launchctl`).

Comprueba la IP del equipo (Windows: `ipconfig`; Linux/macOS: `ip a`).
Ejemplo: `192.168.1.50`.

## 3. Configurar la app

Copia `local.properties.example` como `local.properties` y pon la IP real:

```properties
QWEN_BASE_URL=http://192.168.1.50:11434/v1
```

> No pongas `127.0.0.1`: eso es "el propio teléfono". Tiene que ser la IP del
> equipo donde corre Ollama.

Construye el APK como siempre (`./gradlew assembleDebug`).

## 4. Probar que funciona (importante)

Antes de armar todo con el teléfono, ejecuta en el equipo de Ollama:

```bash
python3 scripts/test_qwen_ollama.py --host http://127.0.0.1:11434
```

El script reproduce exactamente lo que hace la app (mismo prompt, mismas
herramientas, mismo bucle de function calling) y reporta PASS/FAIL por
escenario: sensibilidad, modo SAFE/CHAT, avisos, buzzer, lectura de
telemetría real y conversación sin herramientas. Con el modelo real tardará
un poco (≈5–20 s por pregunta en CPU; menos con GPU).

## Notas de rendimiento y calidad

- La primera pregunta después de unos 5 min "recarga" el modelo (segundos);
  Ollama lo mantiene caliente solo para ese lapso.
- El contexto por defecto de Ollama (4096 tokens) alcanza con holgura para el
  prompt + herramientas + memoria de Karbys (≈2000 tokens en el peor caso).
  Si algún día el prompt crece mucho, crea un modelo propio con más contexto:
  `ollama create qwen2.5:1.5b-waycore` con un Modelfile que incluya
  `PARAMETER num_ctx 8192`, y apunta `QWEN_BASE_URL` a ese nombre.
- Temperatura 0.7 y `max_tokens: 512`: respuestas cortas y estables.
- El cliente incluye un parser de respaldo: si el modelo pequeño imprime la
  llamada de herramienta como JSON dentro del texto en vez de en
  `tool_calls`, la app la detecta igual.
- El 1.5B es bueno para los 5 comandos simples de WayHat y charlas cortas.
  Si notan que falla seguido con instrucciones largas, `qwen2.5:3b` es el
  siguiente salto (≈2 GB) y llama a funciones de forma más estable.

## Bluetooth

Igual que antes: el teléfono debe tener `WayHat-Karbys` vinculado. WayCore
abre la conexión SPP automáticamente con el UUID estándar de Bluetooth Classic.
WayHat mantiene su seguridad local aunque el teléfono o el servidor del
modelo no estén disponibles.
