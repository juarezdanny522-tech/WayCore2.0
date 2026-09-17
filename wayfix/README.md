# WayCore 0.6.0 + WayHat v6.1 — sombrero con ESP32, voz con Gemini

WayCore es la app de Android que escucha, habla y controla al WayHat: un sombrero con
ESP32‑WROOM‑32 que mide distancias con ultrasónico y LiDAR, temperatura y humedad, y avisa
por buzzer cuando algo está cerca. WayHat funciona **aunque no haya Internet ni Gemini**:
la seguridad vive en el sombrero; la app solo la complementa con voz.

```
Teléfono (WayCore)  ──Bluetooth Classic SPP──►  Sombrero (WayHat / ESP32)
      │                                                  │
      ├─ Karbys: hotword + TTS + recordatorios           ├─ 3x HC-SR04 (derecha, izq., atrás)
      └─ Gemini (function calling, lista blanca)         ├─ TF-Luna (frontal, LiDAR)
                                                          ├─ DHT11 (temp / humedad)
                                                          └─ Buzzer
```

## 1. Instalar el APK en el teléfono

1. Descarga `WayCore-latest-debug.apk` desde el release
   `https://github.com/<tu-usuario>/WayCore2.0/releases/tag/waycore-latest`.
2. En Android: Ajustes → Apps → *Instalar apps desconocidas* → permite al navegador o
   al administrador de archivos.
3. Instala el APK. Si ya tenías una WayCore antigua con otra firma, hay que
   **desinstalarla primero** (error "las firmas no coinciden" = keystore distinto).
4. Abre WayCore una vez y acepta micrófono, ubicación, Bluetooth y notificaciones.
   Android 12+ exige aceptar el permiso antes de que la app pueda quedarse escuchando.
5. Ajustes de Karbys → pega tu clave de Gemini → GUARDAR. No hay ninguna clave en el
   repositorio ni dentro del APK de GitHub Actions.

La app está pensada para usarse sin mirar: el botón grande alterna "escuchar", y todo lo
que dice Karbys se muestra también en la pantalla como texto, para que TalkBack lo lea.

## 2. Vincular el sombrero

1. Grabe el `wayfix/WayHat_v6_WayCore.ino` en el ESP32 (Arduino IDE 2.x, placa *ESP32 Dev
   Module*, Bluetooth Classic). Necesita la librería **DHT sensor library** de Adafruit.
2. En Ajustes de Android → Bluetooth, vincula el dispositivo **`WayHat-Karbys`**.
   WayCore no escanea ni pareja: solo abre un canal SPP sobre un dispositivo ya vinculado.
3. Abre WayCore. Debe decir "WayHat conectado por Bluetooth" en pocos segundos.

WayCore usa el UUID SPP estándar `00001101-0000-1000-8000-00805F9B34FB` y reintenta con
socket inseguro y con el método oculto `createRfcommSocket(1)` si el teléfono falla.

## 3. Clave de Gemini: dos formas, escoge una

| Forma | Dónde | Cuándo conviene |
| --- | --- | --- |
| En la app | Sección **Ajustes de Karbys** | Para repartir el mismo APK a varias personas. La clave queda solo en el teléfono. |
| Al compilar | `wayfix/local.properties` → `GEMINI_API_KEY=...` | Build propio, o CI con el secret `GEMINI_API_KEY`. |

Crea la clave en <https://aistudio.google.com/apikey>. En un repo **público** nunca se
debe subir la clave: `local.properties` ya está en `.gitignore`.

Modelo: WayCore prueba en orden `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`,
`gemini-3-flash-preview`, `gemini-2.5-flash`. Si Google retira uno, la app sigue funcionando;
si quieres forzar uno, escríbelo en el campo *Modelo*.

## 4. Qué puede decirle la persona a Karbys

Hotword: **"Oye Karbys"** (tolera *karvis, carbis, karby…*, y también responde solo con el
nombre). Después de cada respuesta hay una ventana de 6 s para seguir hablando sin hotword.

Comandos locales (funcionan sin Internet): hora, fecha, batería, ubicación,
"modo seguro", "modo charla", "activa los avisos", "sensibilidad a 80 centímetros",
"estado de WayHat", "prueba el buzzer", "recuérdame tomar agua en 20 minutos",
"mis recordatorios", "pausa Karbys".
Lo demás se va a Gemini, que puede llamar a las cinco funciones permitidas.

## 5. Compilar tú mismo

### Opción A — Android Studio
1. *Open project* → carpeta `wayfix/`.
2. Si falta el wrapper: `gradle wrapper --gradle-version 8.11.1`
   (o deja que Android Studio descargue Gradle; no hace falta el `.jar`).
3. *Build → Build Bundle(s)/APK(s) → Build APK(s)*. El APK queda en
   `wayfix/app/build/outputs/apk/debug/`.

### Opción B — línea de comandos (Linux/macOS/Windows con WSL)
Requiere JDK 17, Android SDK con `platforms;android-35` y `build-tools;35.0.0`, y Gradle 8.9+.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
cd wayfix
gradle assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
gradle installDebug          # o instalarlo directo por USB con adb
```

`gradle assembleRelease` también funciona sin keystore: se firma con la debug key para
que puedas instalarlo. Para un release publicable en Play, crea tu keystore y escribe
`wayfix/keystore.properties`:

```properties
storeFile=../wayhat.jks
storePassword=...
keyAlias=wayhat
keyPassword=...
```

### Opción C — GitHub Actions (lo que se usó para este APK)
`.github/workflows/build-apk.yml` compila en cada push que toque `wayfix/`, sube el APK
como artifact y lo publica en el release rolling `waycore-latest`.

## 6. Protocolo Bluetooth

Teléfono → sombrero (una línea JSON + `\n`):

```json
{"type":"config","id":"k1717...","threshold":80,"mode":"SAFE","buzzer":true}
{"type":"command","id":"k1","name":"BUZZER_TEST"}     // o "SENSORS"
```

Sombrero → teléfono:

```json
{"type":"telemetry","available":true,"right":45,"left":-1,"rear":120,"tf":88,
 "closest":45,"temp":29.4,"hum":61.2,"dht_ok":true,"threshold":80,"mode":"SAFE",
 "buzzer":true,"right_ok":true,"left_ok":false,"rear_ok":true,"tf_ok":true}
{"type":"ack","id":"k1","ok":true,"message":"OK","threshold":80,"mode":"SAFE","buzzer":true}
```

Reglas que no se pueden romper:
- `threshold` siempre entre 20 y 150 cm; el ESP32 vuelve a recortarlo con `constrain()`.
- El sombrero **solo** entiende `config`, `command` con `BUZZER_TEST/SENSORS/SAFE/CHAT`.
  Cualquier otra cosa responde `ok:false, "Comando no permitido"`. No hay forma de que un
  modelo mande comandos arbitrarios.
- `-1` en una distancia significa "sin lectura", no "obstáculo". Nunca se debe interpretar
  como 0.
- El ack ahora incluye el estado real después de aplicar la orden, así que Karbys puede
  confirmar con datos en vez de prometer.

## 7. Solución de problemas

| Síntoma | Causa probable y arreglo |
| --- | --- |
| "Vincula WayHat-Karbys en Bluetooth" | No está pareado, o el nombre del dispositivo cambió. Vuelve a parear. |
| "WayHat aún no está conectado" al mandar orden | La app apenas abre el socket; toca RECONECTAR WAYHAT. |
| Karbys nunca escucha y no hay error | Faltan permisos, o el teléfono no tiene servicio de reconocimiento de voz (WayCore lo avisa en pantalla). Requiere `<queries>` para `android.speech.RecognitionService` (ya está en el manifiesto). |
| "Falta configurar la clave de Gemini" | Guarda la clave en Ajustes de Karbys. |
| "La clave de Gemini no es válida" | Clave incorrecta, o proyecto de Cloud con facturación bloqueada. |
| "vas pasado de peticiones" | Cuota del plan gratuito de la clave: espera o sube de plan. |
| El buzzer suena sin parar | Revisa el formato del TF-Luna: debe estar en 9-byte/cm (default). Si está en 9-byte/mm, pon `TF_UNIT_CM false`. |
| Temperatura siempre "sin lectura" | El DHT11 es de 1 Hz y necesita ~1 s entre lecturas; el firmware ya lo respeta. Revisa el pull-up de 10 kΩ en el pin de datos. |
| Distancias locas en los HC-SR04 | Ecos cruzados entre sensores. El firmware separa 6 ms cada lectura; si sigues viendo saltos, aumenta `HC_GAP_MS` o cambia el orden. Ojo: el ECHO del HC-SR04 es 5 V, un divisor de voltaje evita quemar el pin. |
| El teléfono no vibra cerca de obstáculos | El teléfono solo vibra en modo SAFE. El buzzer del sombrero es la alerta primaria. |
