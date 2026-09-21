package com.wayhat.waycore

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Catálogo y almacenamiento del modelo de IA local (.task de MediaPipe).
 *
 * El modelo NO viene dentro del APK (pesa ~300 MB). Se obtiene por una de
 * estas dos rutas:
 *  1) Descarga desde la propia app (ModelDownloadService).
 *  2) Importación manual: el usuario copia el .task a su celular y lo
 *     selecciona con el botón "Importar archivo".
 */
data class ModelSpec(
    val id: String,
    val label: String,
    val shortName: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val needsToken: Boolean,
    val note: String
)

object LocalModelManager {
    const val PREFS = "waycore_local_ai"
    const val KEY_SELECTED_ID = "selected_id"
    const val KEY_CUSTOM_URL = "custom_url"
    const val KEY_HF_TOKEN = "hf_token"
    const val KEY_MODE = "karbys_mode" // auto | local | gemini

    const val MODE_AUTO = "auto"
    const val MODE_LOCAL = "local"
    const val MODE_GEMINI = "gemini"

    // Tamaño mínimo para considerar que un .task está completo.
    const val MIN_MODEL_BYTES = 50L * 1024L * 1024L

    val OFFICIAL_GEMMA = ModelSpec(
        id = "gemma-3-270m-it-q8",
        label = "Gemma 3 270M Q8 (oficial de Google, recomendado)",
        shortName = "Gemma oficial",
        url = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.task",
        fileName = "gemma3-270m-it-q8.task",
        sizeBytes = 318_767_104L,
        needsToken = true,
        note = "Requiere cuenta gratis de HuggingFace, aceptar la licencia Gemma y pegar un token. Ver la guía."
    )

    val COMMUNITY_ALT = ModelSpec(
        id = "functiongemma-270m-alt",
        label = "FunctionGemma 270M (alternativo de la comunidad)",
        shortName = "Alternativo",
        url = "https://huggingface.co/2796gauravc/artha-functiongemma-270m-mediapipe/resolve/main/artha_functiongemma_v9_0_0.task",
        fileName = "functiongemma-270m-alt.task",
        sizeBytes = 271_000_000L,
        needsToken = false,
        note = "Alternativo de ~271 MB. Puede descargarse sin token."
    )

    val CATALOG = listOf(OFFICIAL_GEMMA, COMMUNITY_ALT)

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getSelectedId(ctx: Context): String =
        prefs(ctx).getString(KEY_SELECTED_ID, OFFICIAL_GEMMA.id) ?: OFFICIAL_GEMMA.id

    fun setSelectedId(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_SELECTED_ID, id).apply()
    }

    fun getCustomUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_CUSTOM_URL, "").orEmpty().trim()

    fun setCustomUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_CUSTOM_URL, url.trim()).apply()
    }

    fun getHfToken(ctx: Context): String =
        prefs(ctx).getString(KEY_HF_TOKEN, "").orEmpty().trim()

    fun setHfToken(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY_HF_TOKEN, token.trim()).apply()
    }

    fun getMode(ctx: Context): String {
        val m = prefs(ctx).getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO
        return if (m == MODE_LOCAL || m == MODE_GEMINI) m else MODE_AUTO
    }

    fun setMode(ctx: Context, mode: String) {
        val m = if (mode == MODE_LOCAL || mode == MODE_GEMINI) mode else MODE_AUTO
        prefs(ctx).edit().putString(KEY_MODE, m).apply()
    }

    fun modeLabel(mode: String): String = when (mode) {
        MODE_LOCAL -> "Solo local (sin internet)"
        MODE_GEMINI -> "Solo Gemini (nube)"
        else -> "Automático (local primero)"
    }

    /** Carpeta privada de la app donde vive el modelo. */
    fun modelDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Especificación efectiva: URL personalizada si el usuario la escribió, si no la del catálogo. */
    fun effectiveSpec(ctx: Context): ModelSpec {
        val custom = getCustomUrl(ctx)
        if (custom.isNotBlank()) {
            val name = custom.substringAfterLast("/").substringBefore("?")
                .ifBlank { "modelo-personalizado.task" }
                .let { if (it.endsWith(".task", ignoreCase = true)) it else "$it.task" }
            return ModelSpec(
                id = "custom",
                label = "Modelo personalizado",
                shortName = "Personalizado",
                url = custom,
                fileName = name,
                sizeBytes = 0L,
                needsToken = getHfToken(ctx).isNotBlank(),
                note = "URL escrita por el usuario."
            )
        }
        val id = getSelectedId(ctx)
        return CATALOG.firstOrNull { it.id == id } ?: OFFICIAL_GEMMA
    }

    fun targetFile(ctx: Context, spec: ModelSpec = effectiveSpec(ctx)): File =
        File(modelDir(ctx), spec.fileName)

    fun tempFile(ctx: Context, spec: ModelSpec = effectiveSpec(ctx)): File =
        File(modelDir(ctx), spec.fileName + ".tmp")

    /** Devuelve el primer .task válido que exista en la carpeta, sea cual sea su nombre. */
    fun anyModelFile(ctx: Context): File? {
        val dir = modelDir(ctx)
        val files = try { dir.listFiles()?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        return files
            .filter { it.isFile && it.name.endsWith(".task", ignoreCase = true) && it.length() >= MIN_MODEL_BYTES }
            .maxByOrNull { it.lastModified() }
    }

    fun isModelReady(ctx: Context): Boolean = anyModelFile(ctx) != null

    fun modelStatusText(ctx: Context): String {
        val ready = anyModelFile(ctx)
        if (ready != null) {
            return "Modelo instalado: ${ready.name} (${formatMB(ready.length())}). Listo para usarse sin internet."
        }
        val spec = effectiveSpec(ctx)
        val tmp = tempFile(ctx, spec)
        if (tmp.exists() && tmp.length() > 0) {
            return "Descarga incompleta de ${spec.fileName} (${formatMB(tmp.length())} de aprox. ${formatMB(spec.sizeBytes)}). Puedes reanudarla."
        }
        return "Sin modelo local. Descarga ${spec.shortName} (aprox. ${formatMB(spec.sizeBytes)}) o importa un .task."
    }

    fun deleteModels(ctx: Context): Boolean {
        var ok = true
        try {
            modelDir(ctx).listFiles()?.forEach {
                if (it.isFile && (it.name.endsWith(".task", ignoreCase = true) || it.name.endsWith(".tmp"))) {
                    if (!it.delete()) ok = false
                }
            }
        } catch (_: Exception) { ok = false }
        return ok
    }

    fun hasUsableSpace(ctx: Context, neededBytes: Long): Boolean {
        return try { modelDir(ctx).usableSpace > neededBytes } catch (_: Exception) { true }
    }

    fun formatMB(bytes: Long): String {
        if (bytes <= 0) return "?"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }

    /**
     * Los bundles .task son archivos ZIP: siempre empiezan con "PK".
     * Esto detecta descargas corruptas o respuestas HTML/JSON de error.
     */
    fun looksLikeTaskBundle(file: File): Boolean {
        return try {
            if (!file.exists() || file.length() < MIN_MODEL_BYTES) return false
            file.inputStream().use { ins ->
                val head = ByteArray(2)
                if (ins.read(head) != 2) return false
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() // 'P' 'K'
            }
        } catch (_: Exception) { false }
    }

    /**
     * Copia un .task elegido por el usuario (Storage Access Framework) a la
     * carpeta privada, con progreso 0-100. Devuelve Result con el archivo final.
     */
    suspend fun importFromUri(
        ctx: Context,
        uri: Uri,
        onProgress: (percent: Int, detail: String) -> Unit
    ): Result<File> {
        return try {
            val resolver = ctx.contentResolver
            val total = try {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            } catch (_: Exception) { -1L }
            if (total > 0 && !hasUsableSpace(ctx, total + 100L * 1024L * 1024L)) {
                return Result.failure(IllegalStateException("Sin espacio libre suficiente en el celular."))
            }
            val dest = File(modelDir(ctx), "modelo-importado.task.tmp")
            var copied = 0L
            var lastPct = -1
            resolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0) {
                            val pct = ((copied * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct, "Importando… ${formatMB(copied)} de ${formatMB(total)}")
                            }
                        } else {
                            onProgress(-1, "Importando… ${formatMB(copied)}")
                        }
                    }
                }
            } ?: return Result.failure(IllegalStateException("No se pudo leer el archivo elegido."))
            if (!looksLikeTaskBundle(dest)) {
                dest.delete()
                return Result.failure(
                    IllegalStateException("El archivo no parece un modelo .task válido (muy pequeño o corrupto).")
                )
            }
            val finalFile = File(modelDir(ctx), "modelo-importado.task")
            if (finalFile.exists()) finalFile.delete()
            if (!dest.renameTo(finalFile)) {
                dest.copyTo(finalFile, overwrite = true)
                dest.delete()
            }
            onProgress(100, "Modelo importado: ${finalFile.name}")
            Result.success(finalFile)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Importación fallida: ${e.message}"))
        }
    }
}
