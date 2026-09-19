package com.wayhat.waycore

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech

/**
 * Verifica compatibilidad para gama media-alta.
 * Antes fallaba con "modelo no se puede usar" porque GeminiClient usaba gemini-3.1 que no existe.
 * Ahora usa modelos reales y verifica TTS/STT local.
 */
object DeviceCompatibility {

    data class CheckResult(
        val compatible: Boolean,
        val message: String,
        val needsGoogleApp: Boolean = false,
        val needsTts: Boolean = false
    )

    fun check(context: Context): CheckResult {
        val issues = mutableListOf<String>()
        var needsGoogle = false
        var needsTts = false

        // Android mínimo
        if (Build.VERSION.SDK_INT < 26) {
            issues.add("Android ${Build.VERSION.SDK_INT} es muy viejo, necesitas Android 8.0+")
        }

        // RAM y CPU
        try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val totalMb = memInfo.totalMem / (1024 * 1024)
            if (totalMb in 1..1999) {
                issues.add("RAM baja (${totalMb}MB), puede ir lento pero funciona")
            }
        } catch (_: Exception) {}

        // SpeechRecognizer
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                needsGoogle = true
                issues.add("Falta Google App o reconocimiento de voz")
            }
        } catch (_: Exception) {
            needsGoogle = true
        }

        // TTS
        try {
            val intent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            val hasTtsEngine = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
            if (!hasTtsEngine) {
                needsTts = true
                issues.add("Falta motor TTS")
            }
        } catch (_: Exception) {
            needsTts = true
        }

        val compatible = Build.VERSION.SDK_INT >= 26

        val message = if (issues.isEmpty()) {
            "✓ Dispositivo gama media-alta compatible. Android ${Build.VERSION.RELEASE}, ${Runtime.getRuntime().availableProcessors()} núcleos. Gemini funciona en la nube sin descargar modelo."
        } else {
            val base = if (compatible) "✓ Compatible con observaciones: " else "✗ Problemas: "
            base + issues.joinToString(". ") + ". Gemini ya es compatible (usa 2.0-flash / 1.5-flash)."
        }

        return CheckResult(compatible, message, needsGoogle, needsTts)
    }
}
