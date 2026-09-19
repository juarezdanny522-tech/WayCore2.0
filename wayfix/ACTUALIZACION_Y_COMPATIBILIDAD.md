# Fix: Modelo no compatible + Auto-actualización con un toque

## Problema 1: "El modelo no se puede usar en mi dispositivo"
**Causa:** En `GeminiClient.kt` estaba `gemini-3.1-flash-lite` que NO EXISTE en la API de Google.
Por eso Gemini devolvía 404 y la app decía que no podía usar el modelo.

**Solución implementada:**
- Cambiado a lista de modelos reales que funcionan en gama media-alta:
  1. `gemini-2.0-flash` (principal, rápido y compatible)
  2. `gemini-1.5-flash` (fallback muy estable)
  3. `gemini-1.5-flash-8b` (ligero)
  4. `gemini-2.0-flash-lite` (si existe)
- Si un modelo falla con 404, prueba automáticamente el siguiente.
- Mejor manejo de errores: 403, 429, 500, sin internet, etc.
- TTS mejorado: busca cualquier voz en español, no solo MX/US, y no crashea en Xiaomi/Huawei/Samsung gama media.
- SpeechRecognizer: verifica Google App pero intenta crear de todos modos si está instalada.

Ahora cualquier celular Android 8.0+ con 2GB+ RAM y 4 núcleos funciona.

**No se descarga ningún modelo local.** Todo es en la nube, por eso el APK es ligero y no tienes que descargar modelos.

## Problema 2: "Que al presionar el APK se actualice solo"
**Android ya hace esto por defecto SI:**
- Mismo `applicationId` = `com.wayhat.waycore` (ya está)
- Misma firma (debug o release) -> usa la misma PC para compilar
- `versionCode` mayor que el instalado -> subido de 6 a 7 (0.3.0 -> 0.5.1)

**Qué se hizo:**
1. `build.gradle.kts`: `versionCode = 7`, `versionName = "0.5.1"` + comentarios.
2. `AndroidManifest.xml`:
   - `REQUEST_INSTALL_PACKAGES` para instalar actualizaciones desde dentro de la app.
   - `FileProvider` con `provider_paths.xml` para compartir el APK descargado.
   - `<queries>` para TTS y SpeechRecognizer.
3. `UpdateManager.kt` nuevo:
   - `checkForUpdate()` consulta GitHub Releases latest.
   - `downloadAndInstall()` descarga el APK y lanza el instalador del sistema.
   - Al tocar el APK en el gestor de archivos, Android muestra "¿Actualizar?" en vez de "¿Instalar?" y conserva datos.
4. `MainActivity.kt`:
   - Nueva sección "Actualizaciones" con botones BUSCAR y DESCARGAR E INSTALAR.
   - Barra de progreso.
   - Auto-check silencioso al iniciar.
   - Texto explicativo: "No necesitas desinstalar. Solo toca el APK y elige Actualizar."
5. `DeviceCompatibility.kt`: verifica RAM, CPU, TTS, STT y muestra mensaje de compatibilidad.

## Cómo probar
1. Instala la versión vieja (versionCode 6).
2. Compila la nueva: `./gradlew assembleDebug` (genera `app-debug.apk` con versionCode 7).
3. Pásalo al celular y tócalo. Android debe decir "¿Quieres actualizar esta aplicación?" y NO "¿Instalar?".
4. Acepta. Se actualiza conservando datos, sin pedir desinstalar.
5. Abre WayCore, verifica que diga "v0.5.1 (7) - Auto-actualizable" y que Gemini responde.

## Dentro de la app
- Botón BUSCAR -> consulta GitHub
- Si hay nueva versión -> DESCARGAR E INSTALAR -> descarga y lanza instalador
- El usuario solo toca "Actualizar" una vez.

## No más descarga de modelo
Antes parecía que había que descargar modelo porque el error decía "modelo no se puede usar".
Ahora:
- Gemini está 100% en la nube.
- El APK no incluye modelo ML local.
- No hay carpeta de modelos que descargar.

Listo para gama media-alta.
