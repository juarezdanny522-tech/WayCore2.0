# Ramas organizadas - WayCore2.0

Antes tenías 3 ramas `arena/01a0...` con nombres crípticos y revueltas. Ahora está limpio:

## Estructura actual (5 ramas, todas con nombres claros)

### 1. `main`
- Base original v0.5.0
- Solo Gemini, sin cerebro local
- Commit: `2602bd9 Add files via upload`
- Para referencia histórica

### 2. `develop` ⭐ (rama de desarrollo)
- Qwen local 1.5B arreglado para gama media-alta
- Gemini con modelos reales (2.0-flash, 1.5-flash)
- Auto-update APK con un toque (FileProvider)
- VersionCode 9, v0.7.1
- Commit: `20b7fab fix(cerebro-local): compatible gama media-alta + auto-update`
- **Usa esta para seguir desarrollando**

### 3. `stable` ⭐ (rama estable)
- Igual que develop, pero marcada como estable
- Lista para compilar APK final
- VersionCode 9, v0.7.1
- **Usa esta para sacar releases**

### 4. `feature/cerebro-local-qwen`
- Feature branch del cerebro local Qwen 1.5B
- Contiene LocalBrain, ModelManager, Prefs, ToolCalling
- Modelos: Q4_K_M, Q3_K_M, Q2_K, 0.5B
- Para trabajar solo en el cerebro local sin tocar stable

### 5. `release/v0.7.1-qwen-local`
- Release específica v0.7.1
- Para taggear y subir APK a GitHub Releases
- Cuando saques v0.7.2, crea `release/v0.7.2-...`

### 6. `arena/01a0b760-waycore2-0` (sesión actual)
- Tu sesión de Arena, contiene lo mismo que stable pero con merge de fixes
- Commit `c324a1d`
- Puedes borrarla cuando termines, ya está todo en stable/develop

## Qué borré
- `arena/01a0ae86-waycore2-0` -> ahora es `feature/cerebro-local-qwen` / `stable` / `develop`
- `arena/01a0aebc-waycore2-0` -> era intento viejo Qwen, ya no sirve

## Flujo recomendado
```
feature/cerebro-local-qwen -> develop -> stable -> release/vX.Y.Z -> main (si quieres)
```

1. Trabaja en `feature/...`
2. Merge a `develop` para probar
3. Cuando funciona, merge a `stable`
4. Crea `release/vX.Y.Z` y compila APK
5. Sube APK a GitHub Releases -> UpdateManager lo detecta y usuarios actualizan con un toque

## Cómo compilar APK que se actualiza con un toque
- Mismo `applicationId` = `com.wayhat.waycore` (ya está)
- Misma firma (usa misma PC)
- `versionCode` mayor que anterior (ya está en 9, siguiente será 10)

Al tocar el APK nuevo, Android dice "¿Actualizar?" y conserva datos + modelo Qwen descargado.

## Cerebro Qwen incluido
Sí, todas las ramas nuevas (develop, stable, feature, release) traen:
- `LocalBrain.kt` con LiteRT-LM
- `ModelManager.kt` con 4 modelos Qwen2.5
- `ModelTransferService.kt` descarga reanudable
- `Prefs.kt` con modos LOCAL/AUTO/NUBE
- `UpdateManager.kt` para auto-update APK

No necesitas descargar modelo dentro del APK, se descarga aparte con "DESCARGAR IA".
