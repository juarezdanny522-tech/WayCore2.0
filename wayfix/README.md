# WayCore + WayHat v0.5.0 — Telemetría real y control por Gemini

Esta versión usa la versión Bluetooth SPP estable anterior como base.

## Nuevas funciones

- Karbys recibe en cada consulta un contexto fresco con:
  - HC-SR04 derecho, izquierdo y trasero.
  - TF-Luna.
  - Distancia más cercana.
  - Temperatura y humedad DHT11.
  - Estado de cada sensor.
  - Sensibilidad actual.
  - Modo SAFE/CHAT.
  - Estado de avisos sonoros.
  - Estado de conexión de WayHat.
  - Batería del teléfono.
  - Ubicación y antigüedad de la lectura GPS.
  - Hora local.
- Los datos de sensores son tratados como fuente de verdad: Karbys no debe inventar valores.
- Gemini puede usar Function Calling para controlar, de forma limitada y validada:
  - sensibilidad de WayHat (20–150 cm),
  - modo SAFE/CHAT,
  - avisos sonoros,
  - prueba del buzzer,
  - actualización inmediata de telemetría.
- Todos los comandos de hardware tienen una lista blanca y confirmación desde el ESP32.
- El enlace Bluetooth continúa siendo independiente de reconocimiento de voz y TTS.
- WayHat mantiene su seguridad local aunque Gemini o Internet no estén disponibles.

## API key

No hay ninguna clave API incluida. Configúrala en `local.properties`:

`GEMINI_API_KEY=TU_CLAVE`

## Bluetooth

El teléfono debe tener `WayHat-Karbys` vinculado. WayCore abre la conexión SPP automáticamente usando el UUID estándar de Bluetooth Classic.
