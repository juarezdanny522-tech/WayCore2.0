# WayCore + WayHat v0.7.0 — Karbys 100% local en el teléfono

Karbys **piensa dentro del mismo celular** donde vive la app. El cerebro es
**Qwen2.5-1.5B-Instruct** (8-bit, 4096 de contexto) corriendo con
[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) de Google — la misma
librería y el mismo modelo que usa la app oficial *AI Edge Gallery*.

- **Sin PC, sin Ollama, sin API key, sin Internet** para conversar: después
  de descargar el modelo una sola vez, Karbys funciona offline.
- **Function calling nativo**: la librería inyecta las 5 herramientas de
  WayHat en el formato que Qwen2.5 conoce, ejecuta la función cuando el
  modelo la pide y devuelve el resultado hasta que Karbys responde en texto.
- **Seguridad sin cambios**: todas las herramientas pasan por la lista
  blanca de `WayHatService` (comandos válidos + confirmación del ESP32). El
  modelo no puede hacer nada que no esté permitido, aunque se equivoque.

## Requisitos del teléfono

| Requisito | Detalle |
| --- | --- |
| Android | 9.0 (API 28) o más |
| RAM | **6 GB mínimo** (Google lo declara en el modelo), 8 GB recomendado |
| Almacenamiento | ≈1.7 GB libres para el modelo |
| Bluetooth | `WayHat-Karbys` vinculado (como antes) |

Rendimiento esperado (modelo oficial de Google, benchmark en Samsung S25
Ultra): ~26 tokens/segundo en CPU, ~27 en GPU; una respuesta corta de
Karbys tarda unos segundos. La primera carga del modelo toma unos segundos
también.

## Cómo obtener el APK

El APK **se genera solo en GitHub Actions** (no se compila nada local):

1. En GitHub abre el repo → pestaña **Actions** → workflow **Build APK** →
   **Run workflow** (o simplemente haz push a `main`).
2. Espera ~10 minutos. Al terminar aparece una **Release** llamada
   `apk-<commit>` con el APK para descargar.
   (También está en *Artifacts* de la corrida, por 90 días.)
3. Copia el APK al teléfono e instálalo (si es la primera vez, habilita
   "Instalar apps desconocidas" para el navegador/archivador).

## Cómo usarlo

1. Abre WayCore y da los permisos como siempre.
2. En la sección **MODELO LOCAL DE KARBYS** pulsa **DESCARGAR MODELO (≈1.6
   GB)**. Usa Wi-Fi; si se corta, al reintentar **continúa donde quedó**
   (no vuelve a empezar) y al terminar valida que el archivo es el completo.
3. Pulsa **CARGAR MODELO** (o simplemente cierra y reabre la app: si el
   modelo ya está descargado, se carga solo).
4. Cuando diga "Modelo local listo", Karbys ya piensa en el teléfono.
   Desde ese momento **no necesita Internet**.

> Si el teléfono tiene poca RAM libre, cargar el modelo puede fallar.
> Cierra otras apps y vuelve a intentar.

## Bluetooth

Igual que antes: el teléfono debe tener `WayHat-Karbys` vinculado. WayCore
abre la conexión SPP automáticamente con el UUID estándar de Bluetooth
Classic. WayHat mantiene su seguridad local aunque el modelo o el teléfono
no estén disponibles.

## Arquitectura (resumen)

| Pieza | Archivo |
| --- | --- |
| Motor del modelo (LiteRT-LM) + herramientas | `LocalQwen.kt` |
| Descarga (reanudable + validación), carga, estado | `ModelService.kt` |
| Voz, oído, memoria, comandos rápidos locales | `KarbysService.kt` |
| Enlace Bluetooth con WayHat (lista blanca + ack) | `WayHatService.kt` |
| Pantalla (incluye control del modelo) | `MainActivity.kt` |
| Build del APK en la nube | `.github/workflows/build-apk.yml` |

## Notas

- El comando literal ("sensibilidad 80", "modo charla", "prueba el buzzer"…)
  se ejecuta por atajo local sin pasar por el modelo (más rápido); el
  function calling de Qwen2.5 entra cuando la persona parafrasea.
- El archivo del modelo vive en `filesDir/qwen2.5-1.5b-instruct-q8-ctx4096.litertlm`;
  borrarlo (o "Des cargar de la memoria" y borrar datos de la app) lo
  elimina.
- Si en el futuro quieren otro tamaño/contexto, solo cambia `MODEL_URL` y
  `EXPECTED_MODEL_SIZE` en `ModelService.kt` (los archivos oficiales están
  en `litert-community/Qwen2.5-1.5B-Instruct` de Hugging Face).
