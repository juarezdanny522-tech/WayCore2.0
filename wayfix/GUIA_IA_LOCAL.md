# Guía definitiva: IA local de Karbys en tu celular (v0.4.0)

Esta guía deja funcionando la **IA local (sin internet)** de Karbys paso a paso,
más la reparación de la voz ("nunca ha hablado") y de la nube Gemini.

## 1. Qué se instaló en esta versión

| Pieza | Qué hace |
|---|---|
| Motor IA local (`LocalBrain`) | Corre el modelo Gemma 270M dentro del teléfono con MediaPipe. Sin internet. |
| Descarga en la app (`ModelDownloadService`) | Baja el modelo (~320 MB), reanuda si se corta y lo valida. |
| Importación manual | Permite cargar un `.task` copiado por USB desde una PC. |
| Voz a prueba de fallos | Se quitó el desvío forzado a audífono Bluetooth (eso silenciaba a Karbys). Ahora verifica motor, idioma y volumen, y reintenta. |
| Registro visible | Toda pregunta y respuesta sale en pantalla aunque falle el audio. Cada respuesta dice su fuente: `IA local`, `Gemini`, `Regla local` u `Offline`. |
| Selector de motor | Automático (local primero) / Solo local / Solo nube. |
| Diagnóstico | Revisa 10 puntos (voz, volumen, micrófono, dictado, modelo, RAM, CPU, clave, internet, WayHat) y los habla. |
| Nube auto-reparable | Ya no usa un modelo fijo muerto: pregunta a Google qué modelos existen y elige uno válido. |

Orden de respuesta en modo Automático:

1. **Reglas locales** (hora, fecha, batería, ubicación, WayHat, recordatorios, identidad): instantáneas, offline.
2. **IA local** (conversación abierta): offline si el modelo está instalado.
3. **Gemini** (nube): si hay clave + internet.
4. **Respaldo offline**: mensaje útil garantizado. Karbys **nunca se queda callado**.

## 2. Requisitos

- Android 8.0 o superior, de **64 bits** (casi todos desde 2017; el diagnóstico lo confirma).
- **~1 GB libre** (320 MB del modelo + margen).
- RAM: 3 GB o más recomendado.
- WiFi para la primera descarga (una sola vez).
- Opcional: clave de Gemini para el modo nube.

## 3. Ruta A — Descargar el modelo desde la app (recomendado)

El modelo oficial es de Google y su licencia exige 3 pasos gratis (una sola vez):

1. Crea tu cuenta en **https://huggingface.co/join** (gratis).
2. Abre **https://huggingface.co/litert-community/gemma-3-270m-it** y acepta la licencia (botón *Agree/Accept*).
3. Crea un token: foto de perfil → **Settings** → **Access Tokens** → **Create new token** → tipo **Read** → copiar (empieza con `hf_...`).
4. En WayCore, panel **IA LOCAL**: elige **Gemma oficial**, pega el token y pulsa **DESCARGAR**.
5. Espera al 100% (si se corta el internet, pulsa DESCARGAR de nuevo: **reanuda**, no empieza de cero).
6. Al terminar, la app carga el modelo sola y verás *"Modelo instalado… Listo para usarse sin internet."*

> Alternativa sin token: el botón **Alternativo** (~271 MB, de la comunidad) puede
> descargarse sin cuenta. Si falla, usa la Ruta A o B.

## 4. Ruta B — Descargar en PC e importar por USB

1. En la PC (con tu cuenta de HuggingFace y la licencia aceptada), descarga:
   `https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.task`
2. Copia el `.task` al celular (USB, WhatsApp, Drive, como prefieras).
3. En WayCore, panel **IA LOCAL** → **IMPORTAR** → elige el archivo.
4. La app lo valida y lo deja listo.

## 5. Pruebas en orden (hazlas así, una por una)

1. **PROBAR VOZ** → debes *escucharla* y *verla* en el registro.
   - ¿No se escucha pero sí se ve? Sube el **volumen multimedia**, revisa que no haya audífono conectado e instala **Google TTS** + una voz en español. Botón **AJUSTES DE VOZ**.
2. **DIAGNÓSTICO** → todo debe salir `OK`. Si algo sale `FALLO`, la misma línea dice el remedio.
3. Escribe *"hola"* y pulsa **ENVIAR** → debe responder `[Regla local]`.
4. Escribe *"¿quién te creó?"* → debe responder el equipo `[Regla local]`, sin internet.
5. Con modelo instalado, escribe *"cuéntame algo bonito"* → debe responder `[IA local · sin internet]`.
6. **Prueba reina**: activa **modo avión** + modo **Local**, pregunta *"¿qué hora es?"* y luego *"¿cómo estás?"*. Si responde a ambas, la IA local funciona al 100%.
7. **HABLAR** → dicta algo. Si el dictado falla, usa el teclado: la inteligencia es la misma.
8. *"Oye Karbys"* → palabra de activación manos libres.

## 6. Si algo falla (tabla rápida)

| Síntoma | Causa probable | Solución |
|---|---|---|
| No se escucha nada, pero el registro sí muestra respuestas | Volumen en silencio / sin motor TTS / sin voz español | Sube volumen multimedia; instala Google TTS y voz español; AJUSTES DE VOZ |
| Registro vacío, ni siquiera "Probando voz…" | Servicio detenido por el sistema | Reabre la app; quita WayCore del ahorro de batería (Ajustes → Batería → Sin restricciones) |
| Descarga: error 401/403 | Falta aceptar licencia o token | Repite pasos 1-3 de la Ruta A y pega el token |
| Descarga: "no es un modelo válido" | Red interrumpida o sin token | Reanuda con DESCARGAR; verifica token |
| "Sin memoria RAM" | Muchas apps abiertas | Cierra apps; la IA local necesita ~1 GB libres de RAM |
| "No compatible, requiere 64 bits" | Celular muy viejo (32 bits) | Usa modo Nube (Gemini) en ese equipo |
| Dictado no disponible | Falta app de Google actualizada | Actualiza la app de Google; usa teclado mientras tanto |
| Gemini: "clave no válida" | Clave incorrecta o sin API habilitada | Revisa la clave en `local.properties` y que la API esté habilitada en Google AI Studio |
| Gemini: "saturado/cuota" | Límite gratis alcanzado | Espera unos minutos o usa la IA local |

## 7. Notas para desarrolladores

- Motor: `com.google.mediapipe:tasks-genai:0.10.27`, API `LlmInference` + `LlmInferenceSession` (una sesión por pregunta, serializada con `Mutex`).
- Modelo por defecto: `gemma3-270m-it-q8.task` (318 MB). Cualquier `.task` de chat sirve (se elige por URL o importación).
- El `.task` es un ZIP (`PK..` al inicio): esa firma se usa para validar descargas.
- Gemini: `models.list` con caché de 24 h + modelo bueno en caché + 4 nombres de respaldo. Los errores HTTP se clasifican (404→siguiente modelo, 401/403→clave, 429→cuota).
- Compilar el APK: abre la carpeta `wayfix/` en Android Studio, configura `local.properties` (`GEMINI_API_KEY=...`, sin comillas) y usa *Build → Build APK(s)*. El modelo se instala después, dentro de la app.
- `android:largeHeap="true"` está activado para darle aire al modelo en gama media/baja.
