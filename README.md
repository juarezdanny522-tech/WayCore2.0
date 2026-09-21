# WayCore2.0

WayCore es el cerebro digital del proyecto WayHat/WayCorp: el asistente de voz
**Karbys** para celular Android + enlace Bluetooth con el dispositivo físico **WayHat**
(ESP32 con sensores de proximidad), pensado para personas con discapacidad visual.

- App Android + firmware ESP32: carpeta [`wayfix/`](wayfix/).
- **Guía para dejar funcionando la IA local en el celular: [`wayfix/GUIA_IA_LOCAL.md`](wayfix/GUIA_IA_LOCAL.md).**

## Estado (v0.4.0)

- IA local sin internet (MediaPipe + Gemma 270M, ~320 MB, se instala dentro de la app).
- Voz reparada + registro visible + diagnóstico de 10 puntos.
- Nube Gemini con autoselección de modelos válidos.
- Control de WayHat por voz y botones (modo seguro/charla, avisos, sensibilidad 20–150 cm).
- Recordatorios por voz, palabra de activación "Oye Karbys", conversación continua.
