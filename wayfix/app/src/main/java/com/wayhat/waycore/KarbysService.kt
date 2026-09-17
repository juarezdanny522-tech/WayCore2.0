package com.wayhat.waycore

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.*
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class KarbysService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "START"
        const val ACTION_LISTEN = "LISTEN"
        const val ACTION_STOP = "STOP"
        const val ACTION_SHUTDOWN = "SHUTDOWN"
        const val ACTION_GREETING = "GREETING"
        const val ACTION_REMINDER = "REMINDER"
        const val ACTION_TEXT = "TEXT"
        /** Hablar un texto informativo (progreso de descarga, avisos del sistema). */
        const val ACTION_SAY = "SAY"
        /** Aviso de proximidad vindo de WayHat: interrumpe a Karbys. */
        const val ACTION_UI = "com.wayhat.waycore.UI"
        const val CHANNEL = "karbys_assistant"
        const val NOTIFICATION_ID = 2401
        private const val CONTINUATION_SILENCE_MS = 4500L
        private const val CONTINUATION_WINDOW_MS = 6000L
        private const val COMMAND_RETRY_DELAY_MS = 180L

        /**
         * Instrucción para el modelo local. Es mucho más corta que la de Gemini a propósito:
         * cada token del system prompt es prefill, y el prefill es lo que hace sentir lento un
         * modelo en el teléfono. Con 0.5B hay que pedir una sola cosa: respuestas de dos frases.
         */
        private const val LOCAL_SYSTEM =
            "Eres Karbys, el asistente de una persona ciega que usa un sombrero con sensores. " +
            "Hablas español latinoamericano natural y corto. Cuando hables, usa dos frases como máximo. " +
            "Usa solo los datos reales que vienen en el mensaje y nunca inventes cifras: si un dato " +
            "dice sin lectura o no aparece, di que no lo tienes. No uses markdown, listas, emojis ni " +
            "comillas. WayCore se pronuncia guaycor y WayHat guayjat."
    }

    private var recognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var hotwordMode = true
    private var processing = false
    private var pausedByUser = false
    private var conversationMode = false
    private var batteryWarningSent = false
    private val memory = mutableListOf<ConversationTurn>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    /** Fragmentos del modelo local que aún no se hablaron (se vacían por frase completa). */
    private val pendingSpeech = StringBuilder()
    private val speechPeek = StringBuilder()
    private var speechDecided = false
    private var speechSuppressed = false
    private var streamedAny = false
    @Volatile private var speechInterrupted = false
    private var continuationTimeout: Runnable? = null
    private var continuationDeadline = 0L
    private var recognizerGeneration = 0L
    private var listening = false
    private var restartAllowedAt = 0L
    private var batteryReceiver: BroadcastReceiver? = null
    private lateinit var audioManager: AudioManager

    /**
     * WayHat avisa de un obstáculo mientras Karbys está hablando o generando: el aviso de
     * seguridad manda. Se corta el TTS, se detiene la generación del modelo y se habla la
     * distancia. Sin esto, el sombrero avisaría por buzzer mientras la voz tapa el entorno.
     */
    private val alertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WayHatService.ACTION_ALERT) return
            if (!Prefs.voiceAlerts(this@KarbysService)) return
            val cm = intent.getIntExtra("distance", -1)
            val talking = ::tts.isInitialized && try { tts.isSpeaking } catch (_: Exception) { false }
            if (!talking && !LocalBrain.generating) return
            speechInterrupted = true
            LocalBrain.cancelGeneration()
            val phrase = if (cm > 0) "Cuidado, obstáculo a $cm centímetros." else "Cuidado, algo muy cerca."
            if (::tts.isInitialized) {
                try {
                    tts.stop()
                    tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "karbys-alert")
                } catch (_: Exception) { }
            }
            publishUiEvent(phrase, true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else startForeground(NOTIFICATION_ID, notification())

        tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "karbys-answer") {
                    main.post { beginContinuationWindow() }
                }
            }
            override fun onError(utteranceId: String?) {
                if (utteranceId == "karbys-answer") main.post { beginContinuationWindow() }
            }
        })

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WayCore:KarbysWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
        registerBatteryMonitor()
        // Android 14 exige declarar que el receptor es privado; si se omite, el sistema lanza
        // SecurityException y Karbys se queda sin el aviso de proximidad hablado.
        try {
            val alertFilter = IntentFilter(WayHatService.ACTION_ALERT)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(alertReceiver, alertFilter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(alertReceiver, alertFilter)
        } catch (_: Exception) { }
        setupRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LISTEN -> beginCommandListening(true)
            ACTION_STOP -> stopEverything(true)
            ACTION_SHUTDOWN -> { stopEverything(false); stopSelf() }
            ACTION_START -> startHotword()
            ACTION_SAY -> {
                val text = intent?.getStringExtra("text").orEmpty().trim()
                if (text.isNotBlank()) main.post { speak(text) }
            }
            ACTION_TEXT -> {
                val text = intent?.getStringExtra("text").orEmpty().trim()
                if (text.isNotBlank()) {
                    cancelContinuationTimeout()
                    askKarbys(text)
                }
            }
            ACTION_GREETING -> firstGreeting()
            ACTION_REMINDER -> {
                val label = intent?.getStringExtra("label") ?: "tu recordatorio"
                main.post {
                    beepAlert()
                    speak("Recordatorio: $label.")
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Karbys activo", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Karbys está activo")
        .setContentText("Puedes decir: Oye Karbys")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun destroyRecognizer() {
        listening = false
        recognizer?.let {
            try { it.cancel() } catch (_: Exception) { }
            try { it.destroy() } catch (_: Exception) { }
        }
        recognizer = null
        recognizerGeneration++
    }

    private fun setupRecognizer() {
        destroyRecognizer()
    }

    private fun createRecognizer(hotword: Boolean): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return null

        destroyRecognizer()
        val generation = recognizerGeneration
        val r = try { SpeechRecognizer.createSpeechRecognizer(this) } catch (_: Exception) { return null }
        recognizer = r
        listening = false

        r.setRecognitionListener(object : RecognitionListener {
            private fun valid(): Boolean = generation == recognizerGeneration && recognizer === r

            override fun onReadyForSpeech(params: Bundle?) {
                if (valid()) listening = true
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (!valid() || !hotword || processing || pausedByUser) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (containsHotword(text)) {
                    listening = false
                    conversationMode = true
                    hotwordMode = false
                    continuationDeadline = System.currentTimeMillis() + CONTINUATION_WINDOW_MS
                    beepStart()
                    destroyRecognizer()
                    main.postDelayed({
                        if (!processing && conversationMode && !pausedByUser) beginCommandListening(false)
                    }, 90)
                }
            }

            override fun onEndOfSpeech() {
                if (!valid()) return
                listening = false
            }

            override fun onResults(results: Bundle?) {
                if (!valid() || processing || pausedByUser) return
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()

                if (hotword) {
                    if (containsHotword(text)) {
                        conversationMode = true
                        hotwordMode = false
                        continuationDeadline = System.currentTimeMillis() + CONTINUATION_WINDOW_MS
                        beepStart()
                        destroyRecognizer()
                        main.postDelayed({
                            if (conversationMode && !pausedByUser) beginCommandListening(false)
                        }, 90)
                    } else {
                        scheduleHotwordRestart(450)
                    }
                } else if (text.isNotBlank()) {
                    cancelContinuationTimeout()
                    continuationDeadline = 0L
                    askKarbys(text)
                } else {
                    retryConversationListeningOrFinish()
                }
            }

            override fun onError(error: Int) {
                if (!valid() || processing || pausedByUser) return
                listening = false
                if (hotword) {
                    scheduleHotwordRestart(500)
                } else if (conversationMode) {
                    retryConversationListeningOrFinish()
                }
            }
        })
        return r
    }

    private fun scheduleHotwordRestart(delayMs: Long) {
        if (pausedByUser || processing || !hotwordMode) return
        val now = System.currentTimeMillis()
        val delay = maxOf(delayMs, restartAllowedAt - now)
        restartAllowedAt = now + delay + 250
        main.postDelayed({
            if (!pausedByUser && !processing && hotwordMode) restartHotword()
        }, delay)
    }

    private fun containsHotword(text: String): Boolean {
        val n = normalize(text)
        val names = listOf(
            "karbys", "karvis", "karbis", "carvis", "carbis", "carbys",
            "karvys", "carvys", "karby", "karvy", "carby", "carvy"
        )
        val wakeWords = listOf("oye", "hey", "ei", "ey", "oiga", "hola", "hoy")

        // First accept the exact/near-exact wake phrase. Android speech
        // recognition often changes the spelling of "Karbys".
        for (name in names) {
            if (wakeWords.any { w -> n.contains("$w $name") }) return true
        }

        // Also accept just the assistant name. This makes the wake word
        // reliable when the recognizer drops the first word ("oye").
        return names.any { name ->
            n == name || n.contains(" $name") || n.startsWith("$name ")
        }
    }

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace("á", "a").replace("é", "e").replace("í", "i")
        .replace("ó", "o").replace("ú", "u")
        .replace(Regex("[^a-z0-9ñ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun firstGreeting() {
        val prefs = getSharedPreferences("waycore", MODE_PRIVATE)
        if (!prefs.getBoolean("greeted", false)) {
            prefs.edit().putBoolean("greeted", true).apply()
            main.postDelayed({ speak("Hola, te estuve esperando. Aquí estoy para ti.") }, 500)
        } else startHotword()
    }

    private fun startHotword() {
        cancelContinuationTimeout()
        pausedByUser = false
        conversationMode = false
        hotwordMode = true
        processing = false
        restartAllowedAt = System.currentTimeMillis() + 700
        scheduleHotwordRestart(700)
    }

    private fun restartHotword() {
        if (processing || !hotwordMode || pausedByUser) return
        if (System.currentTimeMillis() < restartAllowedAt) {
            scheduleHotwordRestart(restartAllowedAt - System.currentTimeMillis())
            return
        }
        main.post {
            if (processing || !hotwordMode || pausedByUser) return@post
            routeToHeadsetIfPossible()
            val r = createRecognizer(true) ?: return@post
            try {
                r.startListening(speechIntent(partial = true, silence = 900L))
                listening = true
            } catch (_: Exception) {
                destroyRecognizer()
                scheduleHotwordRestart(900)
            }
        }
    }

    private fun beginCommandListening(playBeep: Boolean = true) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speak("Necesito permiso para usar el micrófono.")
            startHotword()
            return
        }
        hotwordMode = false
        processing = false
        conversationMode = true
        cancelContinuationTimeout()
        if (playBeep) beepStart()

        main.post {
            if (pausedByUser || processing || !conversationMode) return@post
            routeToHeadsetIfPossible()
            val r = createRecognizer(false)
            if (r == null) {
                finishConversation()
                return@post
            }
            try {
                r.startListening(speechIntent(partial = false, silence = CONTINUATION_SILENCE_MS))
                listening = true
            } catch (_: Exception) {
                destroyRecognizer()
                finishConversation()
            }
        }
    }

    private fun speechIntent(partial: Boolean, silence: Long): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
    }

    private fun beginContinuationWindow() {
        if (pausedByUser || processing) return
        processing = false
        conversationMode = true
        hotwordMode = false
        continuationDeadline = System.currentTimeMillis() + CONTINUATION_WINDOW_MS
        beepReady()
        main.postDelayed({
            if (!pausedByUser && conversationMode && !processing) beginCommandListening(false)
        }, 140)
    }

    /**
     * Android's SpeechRecognizer can report ERROR_NO_MATCH/ERROR_SPEECH_TIMEOUT
     * immediately on some phones even when the silence timeout is configured.
     * During the post-answer conversation window we therefore retry silently
     * instead of ending the conversation. This is what makes Karbys feel like
     * an ongoing voice call rather than a push-to-talk interaction.
     */
    private fun retryConversationListeningOrFinish() {
        if (pausedByUser || processing || !conversationMode) return

        val now = System.currentTimeMillis()
        if (continuationDeadline == 0L) {
            continuationDeadline = now + CONTINUATION_WINDOW_MS
        }

        if (now >= continuationDeadline) {
            finishConversation()
            return
        }

        destroyRecognizer()
        main.postDelayed({
            if (!pausedByUser && conversationMode && !processing && System.currentTimeMillis() < continuationDeadline) {
                beginCommandListening(false)
            } else if (!pausedByUser && conversationMode && !processing) {
                finishConversation()
            }
        }, COMMAND_RETRY_DELAY_MS)
    }

    private fun finishConversation() {
        cancelContinuationTimeout()
        continuationDeadline = 0L
        destroyRecognizer()
        conversationMode = false
        processing = false
        hotwordMode = false
        beepEnd()
        restartAllowedAt = System.currentTimeMillis() + 700
        main.postDelayed({
            if (!pausedByUser) startHotword()
        }, 700)
    }

    private fun askKarbys(text: String) {
        processing = true
        speechInterrupted = false
        continuationDeadline = 0L
        val clean = text.trim()
        publishUiEvent("Escuché: $clean", false)
        scope.launch {
            val direct = executeLocalCommand(clean)
            if (direct != null) {
                rememberTurn(clean, direct)
                withContext(Dispatchers.Main) { processing = false; speak(direct) }
                return@launch
            }
            when {
                shouldUseLocalBrain() -> speakLocally(clean)
                Prefs.hasApiKey(this@KarbysService) -> speakWithGemini(clean)
                else -> {
                    val hint = if (ModelManager.isReady(this@KarbysService)) {
                        "El cerebro local no está listo todavía y no hay clave de Gemini. Los comandos del sombrero sí funcionan: dime, por ejemplo, estado de WayHat."
                    } else {
                        "Ahora mismo no tengo cerebro conversacional. Descarga el modelo local en los ajustes de WayCore, que funciona sin Internet y sin clave, o guarda una clave de Gemini. Los comandos del sombrero sí funcionan."
                    }
                    withContext(Dispatchers.Main) { processing = false; speak(hint) }
                }
            }
        }
    }

    /** AUTO y LOCAL usan el GGUF del teléfono cuando está descargado; CLOUD siempre va a Gemini. */
    private fun shouldUseLocalBrain(): Boolean {
        val mode = Prefs.brainMode(this)
        val ready = ModelManager.isReady(this)
        return when (mode) {
            Prefs.BRAIN_CLOUD -> false
            else -> ready
        }
    }

    /**
     * Conversa con el modelo del teléfono y, si el modelo lo pide, ejecuta acciones reales en
     * WayHat antes de confirmar. El flujo es: generar → si hay ACCIÓN:, ejecutar y volver a
     * generar con el estado fresco del hardware → hablar la confirmación.
     *
     * El texto se habla por frases conforme sale (streaming), pero el primer trozo se retiene
     * hasta saber si era una orden o una respuesta: así jamás se lee un JSON en voz alta.
     */
    private suspend fun speakLocally(userText: String) {
        val ctx = this
        var prompt = localPrompt(userText)
        var answer = ""
        var failure: String? = null
        val executed = StringBuilder()
        withContext(Dispatchers.Main) { beepReady(); publishUiEvent("Pensando en el teléfono…", false) }

        var round = 0
        while (round < ToolProtocol.MAX_ROUNDS) {
            round++
            withContext(Dispatchers.Main) { resetSpeech() }
            val collected = StringBuilder()
            try {
                LocalBrain.ask(ctx, prompt, ToolProtocol.systemPrompt(LOCAL_SYSTEM), Prefs.maxTokens(ctx)) { chunk ->
                    withContext(Dispatchers.Main) {
                        collected.append(chunk)
                        feedLocalChunk(chunk)
                    }
                }
            } catch (e: Throwable) {
                failure = e.message ?: e.javaClass.simpleName
                break
            }

            val (spoken, requested) = ToolProtocol.extract(collected.toString())
            // Un modelo pequeño alucina herramientas. Antes de tocar el hardware se comprueba
            // que la persona lo pidió; las lecturas no cuentan porque no cambian nada.
            val calls = requested.filter {
                ToolProtocol.readOnly(it.name) || ToolProtocol.userAsksForChange(userText)
            }
            if (calls.isEmpty()) {
                if (requested.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        suppressSpeech()
                        publishUiEvent("El modelo quiso cambiar algo sin que lo pidieras; no lo apliqué.", true)
                    }
                    answer = spoken.ifBlank { "No cambio nada del sombrero si no me lo pides." }
                    break
                }
                answer = spoken.ifBlank { collected.toString().trim() }
                break
            }

            withContext(Dispatchers.Main) { suppressSpeech() }
            publishUiEvent("Aplicando en el sombrero: ${calls.joinToString { ToolProtocol.describe(it) }}", false)
            for (call in calls) {
                val result = summarizeToolResult(WayHatService.executeTool(call.name, call.args))
                executed.append(ToolProtocol.describe(call)).append(" -> ").append(result).append("; ")
            }
            if (round >= ToolProtocol.MAX_ROUNDS) {
                answer = spoken.ifBlank { "Ya se lo apliqué al sombrero." }
                break
            }
            prompt = ToolProtocol.followUp(userText, executed.toString().trim(), compactState())
        }

        var finalAnswer = answer.trim()
        if (finalAnswer.isBlank() && failure == null) {
            // Sin texto y sin excepción: el modelo se quedó en blanco o solo emitió la orden.
            finalAnswer = if (executed.isNotBlank()) "Listo, ya lo apliqué al sombrero."
            else "No pude armar una respuesta con el modelo del teléfono. Intenta más despacio o dime el comando directo."
        }
        if (finalAnswer.isBlank() && failure != null) {
            LocalBrain.unload()
            if (Prefs.hasApiKey(ctx)) {
                withContext(Dispatchers.Main) { publishUiEvent("El cerebro local falló; paso a la nube…", false) }
                speakWithGemini(userText)
            } else {
                withContext(Dispatchers.Main) { processing = false; speak("El cerebro local falló: $failure.") }
            }
            return
        }

        val memoryLine = if (executed.isBlank()) finalAnswer
        else "$finalAnswer (recién ejecutado: ${executed.toString().trim().trimEnd(';')})"
        rememberTurn(userText, memoryLine)
        withContext(Dispatchers.Main) {
            if (!speechInterrupted) finishStreamedSpeech(finalAnswer)
            processing = false
            if (streamedAny) awaitTtsIdle { beginContinuationWindow() }
        }
    }

    /** El ack del ESP32 trae el estado real después de aplicar la orden: se lo pasamos tal cual. */
    private fun summarizeToolResult(raw: String): String {
        val obj = try { JSONObject(raw) } catch (_: Exception) { null } ?: return raw.take(140)
        if (!obj.has("ok")) return raw.take(140)
        if (!obj.optBoolean("ok", false)) return obj.optString("message", "WayHat no aceptó la orden").ifBlank { "WayHat no aceptó la orden" }
        val parts = listOfNotNull(
            if (obj.has("threshold")) "sensibilidad ${obj.optInt("threshold")} cm" else null,
            if (obj.has("mode")) "modo ${obj.optString("mode")}" else null,
            if (obj.has("buzzer")) "avisos " + if (obj.optBoolean("buzzer")) "activos" else "apagados" else null
        )
        return if (parts.isEmpty()) "hecho" else "hecho, ${parts.joinToString(", ")}"
    }

    private fun resetSpeech() {
        speechDecided = false
        speechSuppressed = false
        streamedAny = false
        speechPeek.setLength(0)
        pendingSpeech.setLength(0)
    }

    private fun suppressSpeech() {
        speechSuppressed = true
        speechPeek.setLength(0)
        pendingSpeech.setLength(0)
    }

    private fun feedLocalChunk(chunk: String) {
        if (speechSuppressed) return
        if (!speechDecided) {
            speechPeek.append(chunk)
            val peeked = speechPeek.toString()
            if (!peeked.contains('\n') && peeked.length < 24) return
            speechDecided = true
            if (ToolProtocol.looksLikeToolLine(peeked)) {
                suppressSpeech()
                return
            }
            pendingSpeech.append(peeked)
            speechPeek.setLength(0)
            flushPendingSpeech(force = false)
            return
        }
        pendingSpeech.append(chunk)
        flushPendingSpeech(force = false)
    }

    private fun finishStreamedSpeech(fullText: String) {
        if (!speechDecided && speechPeek.isNotEmpty()) {
            pendingSpeech.append(speechPeek.toString())
            speechPeek.setLength(0)
        }
        if (streamedAny) flushPendingSpeech(force = true)
        else if (fullText.isNotBlank()) speak(fullText)
    }

    private suspend fun speakWithGemini(userText: String) {
        val answer = GeminiClient.ask(this, userText, memory.toList(), buildDeviceContext())
        rememberTurn(userText, answer)
        withContext(Dispatchers.Main) {
            processing = false
            speak(answer)
        }
    }

    private fun rememberTurn(user: String, answer: String) {
        memory.add(ConversationTurn(user, answer))
        while (memory.size > 4) memory.removeAt(0)
    }

    /** Cola las frases ya completas del stream. Se llama solo en el hilo principal. */
    private fun flushPendingSpeech(force: Boolean) {
        if (!::tts.isInitialized) return
        while (true) {
            val text = pendingSpeech.toString()
            if (text.isBlank()) { pendingSpeech.setLength(0); return }
            val cut = sentenceEnd(text)
            if (cut < 0) {
                if (!force) return
                pendingSpeech.setLength(0)
                queueUtterance(text.trim())
                return
            }
            pendingSpeech.setLength(0)
            pendingSpeech.append(text.substring(cut))
            queueUtterance(text.substring(0, cut).trim())
        }
    }

    /** Final de frase después de al menos 12 caracteres, para no hablar fragmentos de dos palabras. */
    private fun sentenceEnd(text: String): Int {
        for (i in text.indices) {
            val c = text[i]
            val terminated = c == '.' || c == '!' || c == '?' || c == ':' || c == '\n'
            if (!terminated || i < 12) continue
            if (i == text.length - 1 || text[i + 1] == ' ' || text[i + 1] == '\n') return i + 1
        }
        return -1
    }

    private fun queueUtterance(piece: String) {
        if (piece.isBlank()) return
        streamedAny = true
        val spoken = piece.replace("WayCore", "guaycor", ignoreCase = true)
            .replace("WayHat", "guayjat", ignoreCase = true)
            .replace("WayCorp", "guaycorp", ignoreCase = true)
        routeToHeadsetIfPossible()
        try { tts.speak(spoken, TextToSpeech.QUEUE_ADD, null, "karbys-chunk") } catch (_: Exception) { }
    }

    private fun awaitTtsIdle(after: () -> Unit) {
        val started = System.currentTimeMillis()
        main.postDelayed(object : Runnable {
            override fun run() {
                val speaking = ::tts.isInitialized && try { tts.isSpeaking } catch (_: Exception) { false }
                if (speaking && System.currentTimeMillis() - started < 90_000L) main.postDelayed(this, 350)
                else after()
            }
        }, 400)
    }

    /** Prompt compacto: menos tokens de prefill = menos segundos antes de la primera palabra. */
    private fun localPrompt(userText: String): String {
        val history = memory.takeLast(2).joinToString("\n") { "Usuario: ${it.user} Karbys: ${it.assistant}" }
        return buildString {
            appendLine("DATOS REALES AHORA: ${compactState()}")
            if (history.isNotBlank()) appendLine("CONVERSACION ANTERIOR: $history")
            appendLine("PREGUNTA: $userText")
            append("RESPUESTA HABLADA:")
        }
    }

    private fun compactState(): String {
        val battery = run {
            val info = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = info?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = info?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) level * 100 / scale else -1
        }
        val clock = SimpleDateFormat("H:mm", Locale.ROOT).format(Date())
        val hardware = try {
            val t = JSONObject(WayHatService.telemetrySnapshot())
            if (!t.optBoolean("available", false)) "WayHat desconectado"
            else {
                fun reading(key: String) = t.optInt(key, -1).let { if (it > 0) "$it cm" else "sin lectura" }
                "sensores frente ${reading("tf")}, derecha ${reading("right")}, izquierda ${reading("left")}, atras ${reading("rear")}; " +
                    "obstaculo mas cercano ${reading("closest")}; modo ${t.optString("mode", "SAFE")}; " +
                    "sensibilidad ${t.optInt("threshold", 50)} cm; " +
                    "avisos ${if (t.optBoolean("buzzer", true)) "activos" else "apagados"}; " +
                    (t.opt("temp") as? Number)?.let { "temperatura $it grados, " } ?: ""
            }
        } catch (_: Exception) { "WayHat sin datos" }
        return "$hardware; bateria $battery%; hora $clock"
    }

    private suspend fun executeLocalCommand(text: String): String? {
        val n = normalize(text)
        return when {
            n.contains("bateria") || n.contains("cuanta bateria") || n.contains("nivel de bateria") || n.contains("carga") -> batteryAnswer()
            n.contains("hora") -> "Son las ${SimpleDateFormat("h:mm a", Locale("es", "MX")).format(Date()).replace("a. m.", "de la mañana").replace("p. m.", "de la tarde").replace("a. m", "de la mañana").replace("p. m", "de la tarde").replace("AM", "de la mañana").replace("PM", "de la tarde")}."
            n.contains("fecha") || n.contains("que dia es") || n.contains("que dia estamos") -> "Hoy es ${SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", Locale("es", "MX")).format(Date())}."
            n.contains("ubicacion") || n.contains("donde estoy") || n.contains("donde nos encontramos") -> locationAnswer()
            n.contains("pausa karbys") || n.contains("silencio karbys") -> {
                pausedByUser = true
                hotwordMode = false
                conversationMode = false
                recognizer?.cancel()
                "De acuerdo. Quedo en pausa. Cuando quieras, dime oye karbys."
            }
            n.contains("pon una alarma") || n.contains("crea una alarma") || n.contains("recuérdame") || n.contains("recuerdame") -> scheduleReminder(text)
            n.contains("mis tareas") || n.contains("mis recordatorios") || n.contains("que tengo programado") -> ReminderStore.list(this)
            n.contains("modo seguro") || n.contains("modo de seguridad") -> WayHatService.executeTool("set_wayhat_mode", JSONObject().put("mode", "SAFE"))
            n.contains("modo charla") || n.contains("modo conversacion") -> WayHatService.executeTool("set_wayhat_mode", JSONObject().put("mode", "CHAT"))
            n.contains("activa los avisos") || n.contains("activa el sonido") || n.contains("activa el buzzer") -> WayHatService.executeTool("set_wayhat_alerts", JSONObject().put("enabled", true))
            n.contains("desactiva los avisos") || n.contains("desactiva el sonido") || n.contains("desactiva el buzzer") -> WayHatService.executeTool("set_wayhat_alerts", JSONObject().put("enabled", false))
            n.contains("sensibilidad") && Regex("\\d+").containsMatchIn(n) -> {
                val cm = Regex("\\d+").find(n)?.value?.toIntOrNull() ?: -1
                WayHatService.executeTool("set_wayhat_sensitivity", JSONObject().put("centimeters", cm))
            }
            n.contains("estado de wayhat") || n.contains("estado del wayhat") || n.contains("sensores de wayhat") -> telemetrySpeech()
            n.contains("actualiza los sensores") || n.contains("actualiza wayhat") -> WayHatService.executeTool("refresh_wayhat_telemetry", JSONObject())
            n.contains("prueba el buzzer") || n.contains("prueba el sonido de wayhat") -> WayHatService.executeTool("test_wayhat_alert", JSONObject())
            n.contains("cancela todas las alarmas") || n.contains("borra todos los recordatorios") -> {
                ReminderStore.clear(this)
                "Listo. Eliminé tus recordatorios programados."
            }
            else -> null
        }
    }

    /**
     * Resumen hablado de la telemetría. Se evita leer JSON crudo: para una persona
     * ciega escuchar llaves, comas y comillas no es información, es ruido.
     */
    private fun telemetrySpeech(): String {
        val o = try {
            JSONObject(WayHatService.telemetrySnapshot())
        } catch (_: Exception) {
            return "No pude leer la telemetría de WayHat."
        }
        if (!o.optBoolean("available", false)) return "WayHat no está conectado en este momento."

        val right = o.optInt("right", -1)
        val left = o.optInt("left", -1)
        val rear = o.optInt("rear", -1)
        val tf = o.optInt("tf", -1)
        val closest = o.optInt("closest", -1)
        val threshold = o.optInt("threshold", 50)
        val mode = if (o.optString("mode", "SAFE") == "SAFE") "modo seguro" else "modo charla"
        val alerts = if (o.optBoolean("buzzer", true)) "con avisos sonoros" else "con avisos sonoros apagados"

        fun distance(label: String, value: Int) = if (value > 0) "$label $value centímetros" else "$label sin lectura"

        val temp = o.opt("temp")
        val hum = o.opt("hum")
        val climate = if (temp is Number && hum is Number) " Temperatura $temp grados, humedad $hum por ciento." else ""

        val nearest = if (closest > 0) " El obstáculo más cercano está a $closest centímetros." else " No hay obstáculos dentro del rango."
        return "WayHat conectado, $mode, $alerts, sensibilidad $threshold centímetros. " +
            distance("Frente", tf) + ". " +
            distance("Derecha", right) + ". " +
            distance("Izquierda", left) + ". " +
            distance("Atrás", rear) + "." + nearest + climate
    }

    private fun buildDeviceContext(): String {
        val battery = run {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) level * 100 / scale else -1
        }

        val location = run {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                "unavailable_permission"
            } else {
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .mapNotNull { provider -> try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null } }
                    .maxByOrNull { it.time }
                if (loc == null) "unavailable" else "lat=${loc.latitude}, lon=${loc.longitude}, accuracy_m=${loc.accuracy}, age_ms=${System.currentTimeMillis() - loc.time}"
            }
        }

        val snapshot = WayHatService.telemetrySnapshot()
        val weather = try {
            val reading = JSONObject(snapshot)
            val temp = reading.opt("temp")
            val hum = reading.opt("hum")
            if (temp is Number && hum is Number) {
                "DHT11: temperatura ${temp} grados Celsius, humedad ${hum} por ciento"
            } else {
                "DHT11: sin lectura de temperatura o humedad disponible"
            }
        } catch (_: Exception) { "DHT11: sin lectura disponible" }

        val bt = try {
            JSONObject(snapshot).apply {
                remove("type")
                remove("uptime_ms")
                remove("temp")
                remove("hum")
            }.toString()
        } catch (_: Exception) { snapshot }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("es", "MX")).format(Date())
        return """
Hora local del teléfono: $time
Batería del teléfono: ${if (battery in 0..100) "$battery%" else "unavailable"}
Ubicación del teléfono: $location
$weather
WayHat telemetría JSON (fuente de verdad): $bt
Regla de seguridad: el TF-Luna tiene una zona de protección de mayor alcance que los HC-SR04.
Un valor -1 o null en distancias significa que ese sensor no obtuvo lectura, no que esté vacío ni que haya pared.
""".trimIndent()
    }

    private fun batteryAnswer(): String {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }
        return if (percent in 0..100) "La batería está al $percent por ciento." else "No pude consultar el nivel de batería en este momento."
    }

    private fun locationAnswer(): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "Necesito permiso de ubicación para decirte dónde estás."
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
            ?: return "No pude obtener una ubicación reciente."
        return "Tu ubicación aproximada es latitud ${"%.5f".format(Locale.US, loc.latitude)} y longitud ${"%.5f".format(Locale.US, loc.longitude)}."
    }

    private fun scheduleReminder(text: String): String {
        val parsed = ReminderParser.parse(text) ?: return "Puedo programar recordatorios sencillos. Dime, por ejemplo, recuérdame estudiar a las siete de la tarde."
        ReminderStore.add(this, parsed.label, parsed.triggerAt)
        val whenText = SimpleDateFormat("h:mm a", Locale("es", "MX")).format(Date(parsed.triggerAt))
        return "Listo. Te recordaré ${parsed.label} a las $whenText."
    }

    private fun registerBatteryMonitor() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
                if (percent in 0..5 && !batteryWarningSent) {
                    batteryWarningSent = true
                    main.post {
                        beepAlert()
                        speak("Atención: la batería del celular está al cinco por ciento o menos. Conviene ponerlo a cargar.")
                    }
                } else if (percent > 7) batteryWarningSent = false
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun routeToHeadsetIfPossible() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val preferred = devices.firstOrNull { d ->
                    d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    d.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                }
                if (preferred != null) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.setCommunicationDevice(preferred)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
        } catch (_: Exception) { }
    }

    private fun beepStart() { try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) } catch (_: Exception) {} }
    private fun beepReady() { try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 100) } catch (_: Exception) {} }
    private fun beepEnd() { try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120) } catch (_: Exception) {} }
    private fun beepAlert() { try { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250) } catch (_: Exception) {} }
    private fun cancelContinuationTimeout() { continuationTimeout?.let(main::removeCallbacks); continuationTimeout = null }

    /** Solo informativo: permite que la pantalla muestre qué está pasando con Karbys. */
    private fun publishUiEvent(message: String, speaking: Boolean) {
        try {
            sendBroadcast(
                Intent(ACTION_UI).setPackage(packageName)
                    .putExtra("message", message)
                    .putExtra("speaking", speaking)
            )
        } catch (_: Exception) { }
    }

    private fun speak(text: String) {
        pausedByUser = false
        if (!::tts.isInitialized) { startHotword(); return }
        hotwordMode = false
        conversationMode = true
        val spoken = text.replace("WayCore", "guaycor", ignoreCase = true)
            .replace("WayHat", "guayjat", ignoreCase = true)
            .replace("WayCorp", "guaycorp", ignoreCase = true)
        publishUiEvent(spoken, true)
        beepReady()
        main.postDelayed({
            routeToHeadsetIfPossible()
            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "karbys-answer")
        }, 80)
    }

    private fun stopEverything(message: Boolean) {
        processing = false
        pausedByUser = true
        hotwordMode = false
        conversationMode = false
        cancelContinuationTimeout()
        recognizer?.cancel()
        if (::tts.isInitialized) tts.stop()
        if (message) speak("De acuerdo. Quedé en pausa. Cuando quieras, volvemos a hablar.")
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val preferred = listOf(Locale("es", "MX"), Locale("es", "US"), Locale("es", "CO"), Locale("es", "GT"), Locale("es", "CR"))
        val chosen = preferred.firstOrNull { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        if (chosen != null) tts.language = chosen
        tts.voices?.firstOrNull { v ->
            v.locale.language == "es" && preferred.any { p -> v.locale.country == p.country }
        }?.let { tts.voice = it }
        tts.setSpeechRate(0.93f)
        tts.setPitch(1.04f)
    }

    override fun onDestroy() {
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        try { unregisterReceiver(alertReceiver) } catch (_: Exception) {}
        LocalBrain.cancelGeneration()
        LocalBrain.unload()
        destroyRecognizer()
        if (::tts.isInitialized) tts.shutdown()
        tone?.release(); tone = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

object ReminderStore {
    private const val PREFS = "waycore_reminders"
    private const val KEY = "items"

    fun add(context: Context, label: String, triggerAt: Long) {
        val list = read(context).toMutableList()
        list.add(Reminder(label, triggerAt))
        write(context, list)
        ReminderScheduler.schedule(context, list.lastIndex, label, triggerAt)
    }

    fun list(context: Context): String {
        val items = read(context).filter { it.triggerAt > System.currentTimeMillis() }
        if (items.isEmpty()) return "No tienes recordatorios pendientes."
        return items.sortedBy { it.triggerAt }.joinToString(". ", prefix = "Tienes: ") {
            "${it.label} a las ${SimpleDateFormat("h:mm a", Locale("es", "MX")).format(Date(it.triggerAt))}"
        } + "."
    }

    fun clear(context: Context) {
        read(context).forEachIndexed { index, _ -> ReminderScheduler.cancel(context, index) }
        write(context, emptyList())
    }

    fun remove(context: Context, index: Int) {
        val list = read(context).toMutableList()
        if (index in list.indices) { list.removeAt(index); write(context, list) }
    }

    private fun read(context: Context): List<Reminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val a = org.json.JSONArray(raw)
        return buildList { for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; add(Reminder(o.optString("label"), o.optLong("at"))) } }
    }

    private fun write(context: Context, list: List<Reminder>) {
        val a = org.json.JSONArray()
        list.forEach { a.put(JSONObject().put("label", it.label).put("at", it.triggerAt)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }
}

data class Reminder(val label: String, val triggerAt: Long)
data class ParsedReminder(val label: String, val triggerAt: Long)

object ReminderParser {
    fun parse(text: String): ParsedReminder? {
        val n = text.lowercase(Locale("es", "MX"))
            .replace("á", "a").replace("é", "e").replace("í", "i")
            .replace("ó", "o").replace("ú", "u")

        val relative = Regex("en\\s+(\\d+)\\s+(minutos?|horas?)").find(n)
        if (relative != null) {
            val amount = relative.groupValues[1].toLongOrNull() ?: return null
            val unit = relative.groupValues[2]
            val delta = if (unit.startsWith("hora")) TimeUnit.HOURS.toMillis(amount) else TimeUnit.MINUTES.toMillis(amount)
            val label = text.substringBefore(relative.value)
                .replace(Regex("(?i)(pon una alarma|crea una alarma|recuérdame|recuerdame)"), "")
                .trim().trim('.', ',', ':').ifBlank { "tu recordatorio" }
            return ParsedReminder(label, System.currentTimeMillis() + delta)
        }

        val regex = Regex("(?:a las|a la)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(de la mañana|de la tarde|de la noche|am|pm)?")
        val m = regex.find(n) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val period = m.groupValues[3]
        if (period.contains("tarde") || period.contains("noche") || period == "pm") if (hour < 12) hour += 12
        if ((period.contains("manana") || period == "am") && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null

        val label = text.substringBefore(m.value)
            .replace(Regex("(?i)(pon una alarma|crea una alarma|recuérdame|recuerdame)"), "")
            .replace(Regex("(?i)mañana|manana"), "")
            .trim().trim('.', ',', ':').ifBlank { "tu recordatorio" }

        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (n.contains("mañana") || n.contains("manana")) add(Calendar.DAY_OF_YEAR, 1)
        }
        if (target.timeInMillis <= System.currentTimeMillis()) target.add(Calendar.DAY_OF_YEAR, 1)
        return ParsedReminder(label, target.timeInMillis)
    }
}

object ReminderScheduler {
    private const val ACTION = "com.wayhat.waycore.REMINDER"
    fun schedule(context: Context, id: Int, label: String, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION).putExtra("label", label).putExtra("id", id)
        val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.set(AlarmManager.RTC_WAKEUP, at, pi)
    }
    fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, id, Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) am.cancel(pi)
    }
}

