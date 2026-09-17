package com.wayhat.waycore

import android.content.Context

/**
 * Ajustes del usuario guardados en el teléfono.
 *
 * La clave de Gemini puede venir de dos lugares:
 *  1. `local.properties` / `-PGEMINI_API_KEY` en el momento de compilar (BuildConfig).
 *  2. La pantalla de ajustes de WayCore, que gana sobre la anterior.
 *
 * Así se puede repartir un mismo APK sin dejar la clave dentro del APK ni en Git.
 */
object Prefs {
    private const val FILE = "waycore_prefs"
    private const val KEY_GEMINI = "gemini_api_key"
    private const val KEY_MODEL = "gemini_model"

    fun apiKey(context: Context): String {
        val stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_GEMINI, null)?.trim().orEmpty()
        return stored.ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
    }

    fun hasApiKey(context: Context): Boolean = apiKey(context).isNotBlank()

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_GEMINI, key.trim()).apply()
    }

    /** Modelo de Gemini preferido. Vacío = usar la lista de respaldo de GeminiClient. */
    fun model(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODEL, null)?.trim().orEmpty()

    fun setModel(context: Context, model: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODEL, model.trim()).apply()
    }
}
