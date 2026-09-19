package com.wayhat.waycore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Gestor de auto-actualización para WayCore con cerebro local.
 * - Comprueba la última versión en GitHub Releases
 * - Descarga el APK
 * - Lo instala con un solo toque (sin desinstalar)
 * 
 * Para que "presionar el APK actualice" funcione, Android solo necesita:
 * 1. Mismo applicationId (com.wayhat.waycore)
 * 2. Misma firma (debug/release)
 * 3. versionCode mayor
 */
object UpdateManager {

    // URL directa que SIEMPRE funciona, sin necesidad de API (evita rate limit de GitHub)
    private const val DIRECT_APK_URL = "https://github.com/juarezdanny522-tech/WayCore2.0/releases/download/waycore-latest/WayCore-latest-debug.apk"
    private const val GITHUB_API = "https://api.github.com/repos/juarezdanny522-tech/WayCore2.0/releases/latest"
    private const val GITHUB_FALLBACK_URL = "https://github.com/juarezdanny522-tech/WayCore2.0/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class UpdateInfo(
        val available: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val downloadUrl: String?,
        val changelog: String?,
        val error: String? = null
    )

    suspend fun checkForUpdate(context: Context): UpdateInfo = withContext(Dispatchers.IO) {
        val currentName = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "0.0.0" } ?: "0.0.0"
        val currentCode = try {
            if (Build.VERSION.SDK_INT >= 28) context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            else context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        } catch (_: Exception) { 0L }

        // Intentar API de GitHub primero, pero con fallback robusto a URL directa
        try {
            val request = Request.Builder()
                .url(GITHUB_API)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .addHeader("User-Agent", "WayCore-App")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Rate limit o error: ofrecer descarga directa sin fallar
                    val isRateLimit = resp.code == 403 || resp.code == 429
                    return@withContext UpdateInfo(
                        available = true, // Ofrecer actualización aunque no sepamos versión
                        latestVersion = "latest",
                        currentVersion = currentName,
                        downloadUrl = DIRECT_APK_URL,
                        changelog = null,
                        error = if (isRateLimit) "GitHub limitó consultas (muy común), pero puedes descargar directo el APK que se actualiza solo con un toque."
                        else "No pude consultar versión (código ${resp.code}), pero el APK directo sí funciona: toca DESCARGAR."
                    )
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "").removePrefix("v")
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name", "")
                        if (name.endsWith(".apk", true)) {
                            apkUrl = a.optString("browser_download_url", null)
                            if (!apkUrl.isNullOrBlank()) break
                        }
                    }
                }
                // Si no hay apk en assets, usar URL directa que siempre existe
                if (apkUrl.isNullOrBlank()) apkUrl = DIRECT_APK_URL

                val changelog = json.optString("body", null)
                val latestCode = parseVersionCode(tag)
                val isNewer = if (latestCode > 0 && currentCode > 0) latestCode > currentCode
                else tag != currentName && tag.isNotBlank()

                UpdateInfo(
                    available = isNewer || apkUrl == DIRECT_APK_URL, // Siempre ofrecer si tenemos URL directa
                    latestVersion = tag.ifBlank { "latest" },
                    currentVersion = currentName,
                    downloadUrl = apkUrl,
                    changelog = changelog
                )
            }
        } catch (e: Exception) {
            // Sin internet o error de red: ofrecer URL directa igualmente
            UpdateInfo(
                available = true,
                latestVersion = "latest",
                currentVersion = currentName,
                downloadUrl = DIRECT_APK_URL,
                changelog = null,
                error = "Sin internet o GitHub no respondió (${e.message}), pero el APK directo debería funcionar si tienes internet. URL: $DIRECT_APK_URL - Al tocarlo se actualiza solo."
            )
        }
    }

    private fun parseVersionCode(version: String): Long {
        return try {
            val parts = version.split(".")
            val major = parts.getOrNull(0)?.toLongOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toLongOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toLongOrNull() ?: 0
            major * 10000 + minor * 100 + patch
        } catch (_: Exception) { 0L }
    }

    suspend fun downloadAndInstall(context: Context, url: String, onProgress: (Int) -> Unit = {}): String = withContext(Dispatchers.IO) {
        try {
            if (!url.endsWith(".apk", true)) {
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(intent)
                }
                return@withContext "Abriendo página de actualizaciones en el navegador. Descarga el APK y tócalo, se actualizará solo."
            }

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "No se pudo descargar: ${resp.code}"
                val body = resp.body ?: return@withContext "Respuesta vacía"
                val total = body.contentLength()
                val input = body.byteStream()

                val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "waycore_update.apk")
                if (file.exists()) file.delete()

                FileOutputStream(file).use { out ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val prog = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) { onProgress(prog) }
                        }
                    }
                }

                withContext(Dispatchers.Main) { installApk(context, file) }
                return@withContext "Descargado. Instalando... Toca Actualizar, no desinstales."
            }
        } catch (e: Exception) {
            return@withContext "Error descargando: ${e.message}"
        }
    }

    fun installApk(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
