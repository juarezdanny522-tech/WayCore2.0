package com.wayhat.waycore

import android.content.Context

/**
 * Ajustes del usuario guardados en el teléfono.
 *
 * Dos decisiones de diseño que importan para WayCore:
 *  1. La clave de Gemini puede venir de `local.properties` (BuildConfig) o de la pantalla
 *     de ajustes; la de la app gana. Así se reparte un mismo APK sin dejar claves en Git.
 *  2. El "cerebro" es un ajuste, no una constante: LOCAL (GGUF en el teléfono), CLOUD
 *     (Gemini) o AUTO (local si el modelo está descargado, si no la nube).
 */
object Prefs {
    private const val FILE = "waycore_prefs"
    private const val KEY_GEMINI = "gemini_api_key"
    private const val KEY_MODEL = "gemini_model"
    private const val KEY_BRAIN = "brain_mode"
    private const val KEY_MODEL_URL = "gguf_url"
    private const val KEY_MODEL_NAME = "gguf_name"
    private const val KEY_MODEL_SIZE = "gguf_size"
    private const val KEY_MODEL_SHA = "gguf_sha256"
    private const val KEY_MODEL_OK = "gguf_verified"
    private const val KEY_GPU = "use_gpu"
    private const val KEY_MAX_TOKENS = "max_tokens"
    private const val KEY_VOICE_ALERT = "voice_alerts"

    const val BRAIN_LOCAL = "LOCAL"
    const val BRAIN_CLOUD = "CLOUD"
    const val BRAIN_AUTO = "AUTO"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Clave y modelo de Gemini ---------------------------------------------

    fun apiKey(context: Context): String {
        val stored = prefs(context).getString(KEY_GEMINI, null)?.trim().orEmpty()
        return stored.ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
    }

    fun hasApiKey(context: Context): Boolean = apiKey(context).isNotBlank()

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GEMINI, key.trim()).apply()
    }

    /** Modelo de Gemini preferido. Vacío = usar la lista de respaldo de GeminiClient. */
    fun model(context: Context): String = prefs(context).getString(KEY_MODEL, null)?.trim().orEmpty()

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL, model.trim()).apply()
    }

    // ---- Cerebro activo --------------------------------------------------------

    fun brainMode(context: Context): String =
        prefs(context).getString(KEY_BRAIN, BRAIN_AUTO)?.trim().orEmpty().ifBlank { BRAIN_AUTO }

    fun setBrainMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_BRAIN, mode.uppercase()).apply()
    }

    fun maxTokens(context: Context): Int = prefs(context).getInt(KEY_MAX_TOKENS, 96)

    fun setMaxTokens(context: Context, tokens: Int) {
        prefs(context).edit().putInt(KEY_MAX_TOKENS, tokens.coerceIn(24, 400)).apply()
    }

    fun useGpu(context: Context): Boolean = prefs(context).getBoolean(KEY_GPU, false)

    fun setUseGpu(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GPU, enabled).apply()
        // Cambiar de backend exige recargar el engine.
        LocalBrain.unload()
    }

    fun voiceAlerts(context: Context): Boolean = prefs(context).getBoolean(KEY_VOICE_ALERT, true)

    fun setVoiceAlerts(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE_ALERT, enabled).apply()
    }

    // ---- Modelo local ----------------------------------------------------------

    fun modelUrl(context: Context): String =
        prefs(context).getString(KEY_MODEL_URL, null)?.trim().orEmpty().ifBlank { ModelManager.DEFAULT.url }

    fun setModelUrl(context: Context, url: String) {
        val clean = url.trim()
        val name = clean.substringAfterLast('/').substringBefore('?')
        prefs(context).edit()
            .putString(KEY_MODEL_URL, clean)
            .putString(KEY_MODEL_NAME, if (name.endsWith(".gguf")) name else "")
            .putLong(KEY_MODEL_SIZE, 0L)
            .putString(KEY_MODEL_SHA, "")
            .putBoolean(KEY_MODEL_OK, false)
            .apply()
        LocalBrain.unload()
    }

    fun selectQuantization(context: Context, fileName: String, url: String) {
        prefs(context).edit()
            .putString(KEY_MODEL_NAME, fileName)
            .putString(KEY_MODEL_URL, url)
            .putBoolean(KEY_MODEL_OK, false)
            .apply()
        LocalBrain.unload()
    }

    fun modelName(context: Context): String = prefs(context).getString(KEY_MODEL_NAME, null)?.trim().orEmpty()

    fun modelSize(context: Context): Long = prefs(context).getLong(KEY_MODEL_SIZE, 0L)

    fun modelSha(context: Context): String = prefs(context).getString(KEY_MODEL_SHA, null)?.trim().orEmpty()

    fun isModelVerified(context: Context): Boolean = prefs(context).getBoolean(KEY_MODEL_OK, false)

    fun markModelVerified(context: Context, verified: Boolean) {
        prefs(context).edit().putBoolean(KEY_MODEL_OK, verified).apply()
    }
}
