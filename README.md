# WayCore 2.0

App de Android + firmware ESP32 para el sombrero WayHat: avisos de proximidad por voz
para personas con discapacidad visual.

- `wayfix/` — proyecto Android (Kotlin + Compose) y el sketch del ESP32. Guía completa en
  [wayfix/README.md](wayfix/README.md).
- `.github/workflows/build-apk.yml` — compila el APK en cada push y lo publica en el
  release [`waycore-latest`](https://github.com/juarezdanny522-tech/WayCore2.0/releases/tag/waycore-latest).

## Cómo se usa en corto

1. Instala el APK del release (`WayCore-latest-debug.apk`).
2. Vincula `WayHat-Karbys` por Bluetooth y graba `wayfix/WayHat_v6_WayCore.ino` en el ESP32.
3. Abre la app, acepta permisos y pega tu clave de Gemini en *Ajustes de Karbys*.
4. Di **"Oye Karbys"**.

WayHat sigue protegiendo (buzzer y sensores) aunque el teléfono se quede sin Internet o sin
Gemini.
