# Auditoría WayCore 2.0 — qué estaba roto, qué se arregló, qué sigue pendiente

Revisión de las 16 rutas del repo (app Android en Kotlin/Compose + sketch ESP32 + build Gradle).

## A. Arreglado en esta ronda (impacto directo en el sombrero)

| # | Archivo | Problema | Arreglo |
| --- | --- | --- | --- |
| 1 | `AndroidManifest.xml` | **Karbys no podía escuchar.** Faltaba `<queries>` para `android.speech.RecognitionService`; desde Android 11 la visibilidad de paquetes oculta el servicio y `SpeechRecognizer.isRecognitionAvailable()` devuelve `false` sin lanzar ninguna excepción. Silencio total, sin error en pantalla. | `<queries>` para RecognitionService y `TTS_SERVICE`, y la app ahora avisa en la pantalla si el teléfono no tiene reconocimiento de voz. |
| 2 | `KarbysService.kt` | El contexto que recibe Gemini **borraba temperatura y humedad** (`remove("temp")`, `remove("hum")`, `remove("dht_ok")`), aunque el README promete que el DHT11 viaja en cada consulta. Karbys jamás podía responder "¿qué calor hace?". | Se envía como frase explícita ("DHT11: temperatura 29.4 grados…"), y "sin lectura" cuando el valor es `null`, para que el modelo no invente. |
| 3 | `KarbysService.kt` | A la pregunta "estado de WayHat" la app respondía el **JSON crudo** leído en voz alta: llaves, comas y comillas para una persona ciega. | `telemetrySpeech()`: resumen hablado (modo, avisos, sensibilidad, frente/derecha/izquierda/atrás, obstáculo más cercano, temperatura). |
| 4 | `WayHatService.kt` | El cálculo de la pausa de vibración (`gap`) se hacía **y se tiraba**: el teléfono vibraba igual a 25 cm que a 95 cm, justo el aviso que depende de la cercanía. | `gap` manda: pulso doble cuando el obstáculo está a menos de media zona segura, pulso simple fuera de ella. |
| 5 | `WayHatService.kt` | `catch (_: CancellationException)` alrededor de `withTimeout` también tragaba cancelaciones reales del scope (el servicio al morir) y esperaba 1.8 s en cada orden. | Se distinguen los dos casos y el ack admite 3 s, que es lo que tarda el ESP32 cuando está en mitad de una lectura de sensores. |
| 6 | `MainActivity.kt` | El slider de sensibilidad llegaba a **100 cm** pero el firmware y el README permiten 150 cm: por voz se podía poner 140 y la pantalla luego no lo mostraba bien. | Rango 20–150 cm, con el valor recortado al recibir telemetría (un valor fuera de rango habría reventado el `Slider` de Compose). |
| 7 | `MainActivity.kt` | Sin icono de aplicación (el manifest no declaraba `android:icon`). | Icono adaptativo vectorial (sombrero + ondas ultrasónicas), nítido en cualquier densidad y con variante monocromo para el tema de Android 13+. |
| 8 | `MainActivity.kt` | Con `targetSdk 35`, en Android 15 el contenido se dibuja debajo de la barra de estado. | `safeDrawingPadding()`. |
| 9 | `GeminiClient.kt` | Modelo único y fijo (`gemini-3.1-flash-lite`): cuando Google jubila un id, Karbys se queda mudo con un "no pude obtener una respuesta" que no dice por qué. | Lista con fallback (`3.5-flash-lite → 3.1-flash-lite → 3-flash-preview → 2.5-flash`) + campo *Modelo* en los ajustes, y errores hablables: clave inválida, cuota agotada, modelo no disponible, pregunta bloqueada por seguridad, sin Internet. |
| 10 | `Prefs.kt` + `MainActivity.kt` | La única forma de poner la API key era **recompilar** con `local.properties`, y el repo es público: fácil acabar con una clave quemada en un APK o en Git. | La clave se guarda desde la app (Ajustes de Karbys), con `BuildConfig` solo como respaldo. El mismo APK sirve para todo el equipo. |
| 11 | `ReminderReceiver.kt` | Un recordatorio con el teléfono en segundo plano lanzaba `startForegroundService` desde el receiver: en Android 12+ eso puede reventar con `ForegroundServiceStartNotAllowedException` y el recordatorio se perdía sin más. | Se intenta la voz y, si el sistema lo rechaza, queda una notificación de alta prioridad con el texto del recordatorio. |
| 12 | `WayHat_v6_WayCore.ino` | **DHT11 muestreado cada 250 ms.** Es un sensor de 1 Hz: el resultado es checksum malo y `dht_ok:false` casi siempre (el síntoma coincide con "nunca me dice la temperatura"). | Lectura del DHT limitada a 1 Hz con su propio temporizador, sin tocar el ciclo de 250 ms de los ultrasónicos. |
| 13 | `WayHat_v6_WayCore.ino` | Los tres HC-SR04 se disparaban seguidos: el pulso de uno entra por refracción en el eco del vecino y aparece una distancia falsa corta → pitido fantasma. | 6 ms entre disparos (`HC_GAP_MS`, ajustable). |
| 14 | `WayHat_v6_WayCore.ino` | Si un frame del TF-Luna llegaba partido (el buffer se quedaba con 4 de 9 bytes), el parser se desincronizaba y dejaba de actualizar distancia hasta que el azar lo realineaba. | El frame se descarta tras 80 ms sin bytes. |
| 15 | `WayHat_v6_WayCore.ino` | El buzzer usaba `st.closest` contra `threshold`, mientras el teléfono usa una zona más amplia para el TF-Luna: las dos mitades del aviso no medían lo mismo. | `nearestDanger()` con la misma regla en ambos lados, y el `ack` devuelve el estado real tras aplicar la orden (Karbys confirma con datos, no promete). |

## B. Comprobado y correcto (no lo toquéis)

- **TF-Luna**: `b[2] | (b[3] << 8)` está **bien**. El formato por defecto del módulo es 9 bytes con
  `0x59 0x59 Dist_L Dist_H Amp_L Amp_H Temp_L Temp_H Checksum` en **centímetros**, y el checksum
  es la suma de los 8 primeros bytes. Ojo: si alguien manda `ID_OUTPUT_FORMAT=0x06` (9-byte/mm),
  todas las distancias salen multiplicadas por 10 y el sombrero parecerá mudo; para eso está
  `TF_UNIT_CM`.
- **Unidades del HC-SR04**: `t * 0.0343 * 0.5` con `pulseIn(..., 25000)` da centímetros y un alcance
  máximo de ~4 m, correcto.
- **Seguridad del enlace**: el ESP32 solo acepta `config` y cuatro comandos concretos, recorta el
  umbral con `constrain(…, 20, 150)` y rechaza lo demás con `Comando no permitido`. La app tampoco
  tiene ruta hacia comandos arbitrarios: `executeAllowedTool` es una lista blanca y Gemini solo ve las
  cinco funciones declaradas. Un modelo no puede silenciar el buzzer por iniciativa propia.
- WayHat **no depende del teléfono**: `updateBuzzer()` corre aunque no haya Bluetooth, Internet o
  Gemini. Es la decisión correcta para un dispositivo de movilidad.
- `startForeground(..., MICROPHONE or LOCATION)` y el tipo `connectedDevice` del enlace Bluetooth
  cumplen las reglas de Android 14+.

## C. Pendiente de decidir (no lo cambié para no decidir por vosotros)

1. **`RECEIVE_BOOT_COMPLETED` está declarado pero no hay `BootReceiver`**: tras reiniciar el
   teléfono, Karbys no vuelve a escuchar. Añadirlo de forma ingenua no vale (un servicio con tipo
   `microphone` no puede arrancar desde el background en Android 14+ sin acción del usuario). La
   opción decente: notificación permanente "toca para reactivar Karbys" desde el arranque, o pedir
   al usuario que abra la app. O quitar el permiso si no se va a implementar.
2. **`BLUETOOTH_SCAN`** se pide pero la app nunca escanea (usa `bondedDevices`). Google Play pide
   justificación para ese permiso; se puede quitar de una sola vez (manifest y
   `requestPermissionsIfNeeded`).
3. **`WayCoreService.kt` no está en el manifest ni se usa** — código muerto, se puede borrar.
4. **`WakeLock` parcial de por vida** en `KarbysService`: es lo que permite escuchar el hotword con
   la pantalla apagada, pero drena batería sin límite. Vale la pena revisar `FLAG_WAKE_LOCK` con timeout o
   suspender el hotword cuando WayHat lleve mucho rato desconectado.
5. **Clave de Gemini en `SharedPreferences` sin cifrar.** En un móvil rooteado se puede leer. Para
   el proyecto escolar es aceptable; si se quiere subir de nivel, `security-crypto` está deprecado,
   así que toca Keystore + blob propio, o mejor: un proxy propio que guarde la clave en el servidor.
   (Corrección de tipeo en el punto 4: "vale la pena revisar".)
6. **Firmware y hardware**: el ECHO de un HC-SR04 entrega 5 V y el ESP32 es 3.3 V (divisor con
   1 kΩ / 2 kΩ en cada ECHO). El TF-Luna y el DHT11 van a 3.3 V. El buzzer activo necesita transistor
   o un buzzer activo de 3.3 V, y conviene un botón en el sombrero para silenciar el buzzer sin tocar
   el teléfono.
7. **Sin pruebas automatizadas**: no hay `src/test`. `ReminderParser` y `containsHotword` son las dos
   funciones con más lógica y más fácil de cubrir; `normalize` + la lista de "karvis/carbees" debería
   tener casos como "oye carvis, ¿cuánto falta?" y "que me recuerdes tomar agua en diez minutos".
8. **Licencia y README raíz**: el repo no tiene LICENSE y el `README.md` raíz decía solo "dsa". Ya
   apunta al proyecto; falta decidir licencia (¿Apache-2.0 para que el diseño sea reutilizable?).

## D. Cómo se compila ahora

- Debug y release se compilan igual: `gradle assembleDebug` / `assembleRelease`.
- El release **se firma con la debug key si no hay `wayfix/keystore.properties`**, para que el APK de
  CI sea instalable. Con keystore propia, el release usa esa firma.
- Si falta el wrapper de Gradle: `gradle wrapper --gradle-version 8.11.1` dentro de `wayfix/`.
  `gradle/wrapper/gradle-wrapper.properties` ya fija la versión 8.11.1.
