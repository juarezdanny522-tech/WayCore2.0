package com.wayhat.waycore

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
        const val ACTION_TEST_VOICE = "TEST_VOICE"
        const val ACTION_DIAGNOSE = "DIAGNOSE"
        const val ACTION_SET_MODE = "SET_MODE"
        const val ACTION_RELOAD_MODEL = "RELOAD_MODEL"
        const val CHANNEL = "karbys_assistant"
        const val NOTIFICATION_ID = 2401

        /** Registro visible: la UI muestra todo aunque falle el audio. */
        const val ACTION_LOG = "com.wayhat.waycore.KARBYS_LOG"
        const val EXTRA_KIND = "kind" // user | karbys | status | error
        const val EXTRA_TEXT = "text"
        const val EXTRA_SOURCE = "source"

        const val ACTION_DIAG = "com.wayhat.waycore.KARBYS_DIAG"
        const val EXTRA_DIAG_LINES = "lines"

        const val EXTRA_MODE = "mode"

        private const val CONTINUATION_SILENCE_MS = 4500L
        private const val CONTINUATION_WINDOW_MS = 6000L
        private const val COMMAND_RETRY_DELAY_MS = 180L
    }

    private var recognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var ttsFailed = false
    private var ttsLangDesc = "iniciando…"
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
    private var continuationTimeout: Runnable? = null
    private var continuationDeadline = 0L
    private var recognizerGeneration = 0L
    private var listening = false
    private var restartAllowedAt = 0L
    private var batteryReceiver: BroadcastReceiver? = null
    private lateinit var audioManager: AudioManager

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
                if (utteranceId == "karbys-answer") {
                    log("error", "El motor de voz no pudo reproducir la respuesta.")
                    main.post { beginContinuationWindow() }
                }
            }
        })

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WayCore:KarbysWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
        registerBatteryMonitor()
        setupRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LISTEN -> beginCommandListening(true)
            ACTION_STOP -> stopEverything(true)
            ACTION_SHUTDOWN -> { stopEverything(false); stopSelf() }
            ACTION_START -> startHotword()
            ACTION_TEXT -> {
                val text = intent?.getStringExtra("text").orEmpty().trim()
                if (text.isNotBlank()) {
                    cancelContinuationTimeout()
                    askKarbys(text)
                }
            }
            ACTION_GREETING -> firstGreeting()
            ACTION_TEST_VOICE -> testVoice()
            ACTION_DIAGNOSE -> runDiagnostics()
            ACTION_SET_MODE -> {
                val m = intent?.getStringExtra(EXTRA_MODE).orEmpty()
                if (m.isNotBlank()) {
                    LocalModelManager.setMode(this, m)
                    log("status", "Motor cambiado a: ${LocalModelManager.modeLabel(LocalModelManager.getMode(this))}")
                }
            }
            ACTION_RELOAD_MODEL -> reloadLocalModel()
            ACTION_REMINDER -> {
                val label = intent?.getStringExtra("label") ?: "tu recordatorio"
                main.post {
                    beepAlert()
                    speak("Recordatorio: $label.", source = "Recordatorio")
                }
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------
    // Registro visible (la UI lo muestra línea por línea)
    // ------------------------------------------------------------------

    private fun log(kind: String, text: String, source: String = "") {
        try {
            sendBroadcast(
                Intent(ACTION_LOG).setPackage(packageName)
                    .putExtra(EXTRA_KIND, kind)
                    .putExtra(EXTRA_TEXT, text)
                    .putExtra(EXTRA_SOURCE, source)
            )
        } catch (_: Exception) { }
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("error", "Reconocimiento de voz no disponible. Instala o actualiza la app de Google; mientras tanto usa el botón HABLAR por texto.")
            return null
        }

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

        for (name in names) {
            if (wakeWords.any { w -> n.contains("$w $name") }) return true
        }

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
            main.postDelayed({ speak("Hola, te estuve esperando. Aquí estoy para ti.", source = "Saludo") }, 500)
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
            speak("Necesito permiso para usar el micrófono.", source = "Sistema")
            startHotword()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak(
                "El dictado por voz no está disponible en este celular. Escríbeme con el teclado y te respondo igual.",
                source = "Sistema"
            )
            return
        }
        hotwordMode = false
        processing = false
        conversationMode = true
        cancelContinuationTimeout()
        if (playBeep) beepStart()
        log("status", "Te escucho… habla ahora.")

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
        // Prefiere el paquete sin internet si está instalado; si no, usa la nube.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
    }

    private fun beginContinuationWindow() {
        if (pausedByUser || processing) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Sin dictado no se puede escuchar: quedar quieto (el texto sigue funcionando).
            processing = false
            conversationMode = false
            hotwordMode = false
            return
        }
        processing = false
        conversationMode = true
        hotwordMode = false
        continuationDeadline = System.currentTimeMillis() + CONTINUATION_WINDOW_MS
        beepReady()
        main.postDelayed({
            if (!pausedByUser && conversationMode && !processing) beginCommandListening(false)
        }, 140)
    }

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

    // ------------------------------------------------------------------
    // Cerebro: reglas locales -> IA local -> Gemini -> respaldo offline.
    // Siempre responde algo; cada respuesta indica su fuente.
    // ------------------------------------------------------------------

    private fun askKarbys(text: String) {
        processing = true
        continuationDeadline = 0L
        val clean = text.trim()
        log("user", clean)
        scope.launch {
            val mode = LocalModelManager.getMode(this@KarbysService)

            // 1) Comandos locales instantáneos (siempre offline).
            val direct = executeLocalCommand(clean)
            if (direct != null) {
                said(clean, direct, "Regla local")
                return@launch
            }

            // 2) IA local (sin internet).
            if (mode == LocalModelManager.MODE_AUTO || mode == LocalModelManager.MODE_LOCAL) {
                if (LocalModelManager.isModelReady(this@KarbysService)) {
                    log("status", "Pensando con IA local (sin internet)…")
                    val ans = LocalBrain.answer(this@KarbysService, buildLocalPrompt(clean))
                    if (ans.ok) {
                        said(clean, ans.text, "IA local · sin internet")
                        return@launch
                    }
                    log("error", "IA local falló: ${ans.text}")
                    if (mode == LocalModelManager.MODE_LOCAL) {
                        said(
                            clean,
                            "No pude usar la IA local: ${ans.text} Revisa el panel IA local de la pantalla.",
                            "IA local"
                        )
                        return@launch
                    }
                } else {
                    if (mode == LocalModelManager.MODE_LOCAL) {
                        said(
                            clean,
                            "Aún no hay modelo local instalado. Abre el panel IA local y descarga el modelo, pesa unos 320 MB.",
                            "IA local"
                        )
                        return@launch
                    }
                    log("status", "Sin modelo local instalado. Intentando con Gemini…")
                }
            }

            // 3) Gemini (nube).
            if (mode == LocalModelManager.MODE_AUTO || mode == LocalModelManager.MODE_GEMINI) {
                val key = BuildConfig.GEMINI_API_KEY.trim()
                if (key.isBlank()) {
                    log("error", "Sin clave de Gemini configurada.")
                    if (mode == LocalModelManager.MODE_GEMINI) {
                        said(
                            clean,
                            "Falta configurar la clave de Gemini en WayCore. Cambia a modo automático o instala la IA local.",
                            "Gemini"
                        )
                        return@launch
                    }
                } else if (!hasNetwork()) {
                    log("error", "Sin internet para usar Gemini.")
                    if (mode == LocalModelManager.MODE_GEMINI) {
                        said(clean, "No hay conexión a internet para usar Gemini.", "Gemini")
                        return@launch
                    }
                } else {
                    log("status", "Consultando a Gemini…")
                    val cloudPrefs = getSharedPreferences("waycore_cloud", MODE_PRIVATE)
                    val res = GeminiClient.askDetailed(clean, memory.toList(), buildDeviceContext(), cloudPrefs)
                    if (res.error == null) {
                        said(clean, res.text, "Gemini · ${res.model ?: "nube"}")
                        return@launch
                    }
                    log("error", "Gemini falló: ${res.error}")
                    if (mode == LocalModelManager.MODE_GEMINI) {
                        said(clean, res.text, "Gemini")
                        return@launch
                    }
                }
            }

            // 4) Respaldo offline garantizado: nunca quedarse callado.
            said(clean, offlineFallbackAnswer(clean), "Offline")
        }
    }

    private suspend fun said(user: String, text: String, source: String) {
        memory.add(ConversationTurn(user, text))
        while (memory.size > 4) memory.removeAt(0)
        withContext(Dispatchers.Main) {
            processing = false
            speak(text, source = source)
        }
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
            n.contains("estado de wayhat") || n.contains("estado del wayhat") || n.contains("sensores de wayhat") -> {
                val t = WayHatService.telemetrySnapshot()
                if (t.contains("\"available\":true")) "WayHat está conectado. Lecturas actuales: $t" else "WayHat no está conectado en este momento."
            }
            n.contains("actualiza los sensores") || n.contains("actualiza wayhat") -> WayHatService.executeTool("refresh_wayhat_telemetry", JSONObject())
            n.contains("prueba el buzzer") || n.contains("prueba el sonido de wayhat") -> WayHatService.executeTool("test_wayhat_alert", JSONObject())
            n == "hola" || n == "buenos dias" || n == "buenas tardes" || n == "buenas noches" ||
                n == "hola karbys" || n == "oye karbys" || n == "hey karbys" -> "¡Hola! ¿En qué te ayudo?"
            n.contains("como estas") || n.contains("como te encuentras") -> "Muy bien, lista para ayudarte. ¿Qué necesitas?"
            n == "gracias" || n.startsWith("gracias ") || n.contains("muchas gracias") -> "¡Con gusto! Para eso estoy."
            n.contains("adios") || n.contains("hasta luego") || n.contains("nos vemos") -> "¡Hasta luego! Aquí estaré cuando me necesites."
            n.contains("quien eres") || n.contains("tu nombre") || n.contains("como te llamas") ->
                "Soy Karbys, tu asistente de voz de WayCore, un producto de WayCorp. Puedo conversar, ayudarte con WayHat, darte la hora, la batería y programar recordatorios, incluso sin internet si tienes la IA local instalada."
            n.contains("quien te cre") || n.contains("quienes te crearon") || n.contains("tu equipo") ||
                n.contains("waycorp") || n.contains("origen del proyecto") || n.contains("historia del proyecto") ->
                "WayCore fue creado con amor para todos. Danny Joel Castro Juárez lidera el software y la integración de Karbys. Dennis Alexander es desarrollador de hardware. Daylin Odalis es portavoz y documentadora. Emely Denisse es diseñadora y documentadora. Y agradecen a la licenciada Gloria Yessenia Mármol de Muñoz."
            n.contains("que puedes hacer") || n == "ayuda" || n.contains("ayuda karbys") ||
                n.contains("para que sirves") || n.contains("tus funciones") ->
                "Puedo conversar contigo, decirte la hora, la fecha, la batería y tu ubicación. Controlo WayHat: modo seguro o charla, avisos, sensibilidad y estado de sensores. También programo recordatorios. Dime qué necesitas."
            n.contains("cancela todas las alarmas") || n.contains("borra todos los recordatorios") -> {
                ReminderStore.clear(this)
                "Listo. Eliminé tus recordatorios programados."
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Prompt compacto para el modelo pequeño local (un 270M necesita
    // instrucciones cortas y contexto breve para no divagar).
    // ------------------------------------------------------------------

    private fun buildLocalPrompt(user: String): String {
        val history = if (memory.isEmpty()) "" else memory.takeLast(2).joinToString("\n") {
            "Usuario: ${it.user.take(220)}\nKarbys: ${it.assistant.take(220)}"
        }
        return """
Eres Karbys, asistente de voz en español de El Salvador. Hablas con calidez, en 1 o 2 frases cortas para ser escuchadas. Sin listas, sin emojis, sin markdown. Nunca inventes datos de sensores: usa solo los datos reales de abajo.

Datos reales: ${buildLocalContext()}

${if (history.isBlank()) "" else "Conversación reciente:\n$history\n"}
Usuario: ${user.take(400)}
Karbys:""".trimIndent()
    }

    private fun buildLocalContext(): String {
        val battery = run {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "no disponible"
        }
        val time = SimpleDateFormat("h:mm a", Locale("es", "MX")).format(Date())
        val wayhat = try {
            val o = JSONObject(WayHatService.telemetrySnapshot())
            if (!o.optBoolean("available", false)) "WayHat desconectado"
            else "WayHat conectado, modo ${o.optString("mode", "?")}, más cercano ${o.optInt("closest", -1)} cm " +
                "(der ${o.optInt("right", -1)}, izq ${o.optInt("left", -1)}, atrás ${o.optInt("rear", -1)}, TF ${o.optInt("tf", -1)}), " +
                "sensibilidad ${o.optInt("threshold", 50)} cm"
        } catch (_: Exception) { "WayHat sin datos" }
        return "Hora $time. Batería del teléfono $battery. $wayhat."
    }

    private fun offlineFallbackAnswer(text: String): String {
        val n = normalize(text)
        return when {
            n.contains("hola") || n.contains("buenos dias") || n.contains("buenas tardes") || n.contains("buenas noches") ->
                "¡Hola! Estoy sin conexión a la nube, pero sigo aquí. Puedo darte la hora, la batería, el estado de WayHat o programar recordatorios."
            n.contains("ayuda") || n.contains("que puedes hacer") ->
                "Sin internet puedo decirte la hora, la fecha, la batería, tu ubicación y el estado de WayHat. También programo recordatorios y controlo el modo seguro o charla."
            else ->
                "Estoy sin conexión a la nube y la IA local no respondió. Puedo ayudarte con la hora, la batería, WayHat o recordatorios. Dime qué necesitas."
        }
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

        val bt = try {
            JSONObject(WayHatService.telemetrySnapshot()).apply {
                remove("temp")
                remove("hum")
                remove("dht_ok")
            }.toString()
        } catch (_: Exception) { WayHatService.telemetrySnapshot() }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("es", "MX")).format(Date())
        return """
Hora local del teléfono: $time
Batería del teléfono: ${if (battery in 0..100) "$battery%" else "unavailable"}
Ubicación del teléfono: $location
WayHat telemetría JSON (fuente de verdad): $bt
Regla de seguridad: el TF-Luna tiene una zona de protección de mayor alcance que los HC-SR04.
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

    // ------------------------------------------------------------------
    // Voz (TTS) a prueba de fallos + diagnóstico
    // ------------------------------------------------------------------

    private fun testVoice() {
        scope.launch {
            log("status", "Probando voz…")
            withContext(Dispatchers.Main) {
                processing = false
                speak("Hola, soy Karbys. Si me escuchas, la voz está funcionando perfectamente.", source = "Prueba de voz")
            }
        }
    }

    private fun reloadLocalModel() {
        scope.launch {
            log("status", "Recargando modelo de IA local…")
            LocalBrain.release()
            val ok = LocalBrain.ensureLoaded(this@KarbysService)
            withContext(Dispatchers.Main) {
                if (ok) {
                    log("status", "Modelo de IA local cargado y listo.")
                    speak("Modelo de IA local listo.", source = "IA local")
                } else {
                    val err = LocalBrain.lastError ?: "No hay modelo instalado."
                    log("error", "No se pudo cargar la IA local: $err")
                    speak("No pude cargar la IA local. $err", source = "IA local")
                }
            }
        }
    }

    private fun hasNetwork(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true // Si no se puede comprobar, no bloquear la nube.
        }
    }

    private fun runDiagnostics() {
        scope.launch {
            val lines = mutableListOf<String>()
            fun line(ok: Boolean, text: String) = lines.add("${if (ok) "OK " else "FALLO "} $text")

            // 1) TTS
            line(!ttsFailed && ttsReady, "Voz (TTS): ${if (ttsFailed) "no disponible, instala Google TTS" else ttsLangDesc}")

            // 2) Volumen
            val vol = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }
            val max = try { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }
            line(vol > 0, "Volumen multimedia: $vol de $max${if (vol <= 0) " (súbelo, por eso no se escucha)" else ""}")

            // 3) Micrófono
            val mic = ContextCompat.checkSelfPermission(this@KarbysService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            line(mic, "Permiso de micrófono: ${if (mic) "concedido" else "denegado"}")

            // 4) Dictado
            val stt = try { SpeechRecognizer.isRecognitionAvailable(this@KarbysService) } catch (_: Exception) { false }
            line(stt, "Dictado por voz: ${if (stt) "disponible" else "no disponible (actualiza la app de Google)"}")

            // 5) Modelo local
            val modelFile = LocalModelManager.anyModelFile(this@KarbysService)
            line(modelFile != null, "IA local: ${modelFile?.let { "${it.name} (${LocalModelManager.formatMB(it.length())})" } ?: "sin modelo instalado"}")
            if (modelFile != null && LocalBrain.lastError != null && LocalBrain.status(this@KarbysService) is LocalBrain.State.Error) {
                line(false, "IA local: error al cargar (${LocalBrain.lastError})")
            }

            // 6) RAM
            val mem = ActivityManager.MemoryInfo()
            try { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem) } catch (_: Exception) { }
            val totalGb = mem.totalMem / (1024.0 * 1024.0 * 1024.0)
            line(totalGb >= 2.5, "RAM total: %.1f GB%s".format(totalGb, if (totalGb < 2.5) " (justa para IA local)" else ""))

            // 7) CPU
            val abi = try { Build.SUPPORTED_ABIS.firstOrNull().orEmpty() } catch (_: Exception) { "" }
            line(abi.contains("arm64"), "CPU: ${abi.ifBlank { "desconocida" }}${if (!abi.contains("arm64")) " (la IA local requiere 64 bits)" else ""}")

            // 8) Clave Gemini
            val keyOk = BuildConfig.GEMINI_API_KEY.trim().isNotBlank()
            line(keyOk, "Clave Gemini: ${if (keyOk) "configurada" else "no configurada (la nube no funcionará)"}")

            // 9) Internet
            val net = hasNetwork()
            line(net, "Internet: ${if (net) "disponible" else "no disponible (solo funcionará la IA local)"}")

            // 10) WayHat
            val btOk = try {
                JSONObject(WayHatService.telemetrySnapshot()).optBoolean("available", false)
            } catch (_: Exception) { false }
            line(btOk, "WayHat: ${if (btOk) "conectado" else "no conectado"}")

            val fails = lines.count { it.startsWith("FALLO") }
            withContext(Dispatchers.Main) {
                try {
                    sendBroadcast(
                        Intent(ACTION_DIAG).setPackage(packageName)
                            .putStringArrayListExtra(EXTRA_DIAG_LINES, ArrayList(lines))
                    )
                } catch (_: Exception) { }
                lines.forEach { log("status", it) }
                val summary = if (fails == 0) "Diagnóstico perfecto. Todo funciona." else {
                    val first = lines.firstOrNull { it.startsWith("FALLO") }?.removePrefix("FALLO ") ?: ""
                    "Diagnóstico: $fails puntos por revisar. Primero: $first"
                }
                speak(summary, source = "Diagnóstico")
            }
        }
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
                        speak("Atención: la batería del celular está al cinco por ciento o menos. Conviene ponerlo a cargar.", source = "Batería")
                    }
                } else if (percent > 7) batteryWarningSent = false
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    /**
     * Enrutado de audio natural de Android.
     *
     * Antes se forzaba el modo llamada y Bluetooth SCO siempre, lo que en
     * celulares SIN audífono conectado mandaba la voz a un dispositivo
     * inexistente y Karbys "nunca hablaba". Ahora no se toca nada: si hay
     * audífono, Android ya lo usa solo; si no, usa la bocina.
     */
    private fun routeToHeadsetIfPossible() {
        // Intencionalmente vacío: no forzar SCO/modos. Ver comentario.
    }

    private fun beepStart() { try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) } catch (_: Exception) {} }
    private fun beepReady() { try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 100) } catch (_: Exception) {} }
    private fun beepEnd() { try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120) } catch (_: Exception) {} }
    private fun beepAlert() { try { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250) } catch (_: Exception) {} }
    private fun cancelContinuationTimeout() { continuationTimeout?.let(main::removeCallbacks); continuationTimeout = null }

    private fun pronounce(text: String): String = text
        .replace("WayCore", "guaycor", ignoreCase = true)
        .replace("WayHat", "guayjat", ignoreCase = true)
        .replace("WayCorp", "guaycorp", ignoreCase = true)

    /**
     * Habla el texto. Todo lo dicho TAMBIÉN queda en el registro visible,
     * así que aunque el audio falle, el usuario ve la respuesta en pantalla.
     */
    private fun speak(text: String, source: String = "", clearPause: Boolean = true) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (clearPause) pausedByUser = false
        log("karbys", clean, source)
        if (!pausedByUser) {
            hotwordMode = false
            conversationMode = true
        }
        val spoken = pronounce(clean)
        beepReady()
        main.postDelayed({ deliverToTts(spoken, 0) }, 80)
    }

    private fun deliverToTts(spoken: String, attempt: Int) {
        if (!::tts.isInitialized) return
        if (ttsFailed) {
            log("error", "Voz no disponible: instala 'Google TTS' y una voz en español. La respuesta está visible arriba.")
            return
        }
        if (!ttsReady) {
            // El motor tarda en iniciar: reintentar unos segundos.
            if (attempt < 6) main.postDelayed({ deliverToTts(spoken, attempt + 1) }, 800)
            else log("error", "El motor de voz no responde. Abre el diagnóstico para más detalles.")
            return
        }
        try {
            val vol = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 1 }
            if (vol <= 0) log("error", "El volumen multimedia está en silencio: súbelo para escucharme.")
            val res = tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "karbys-answer")
            if (res == TextToSpeech.ERROR) {
                log("error", "El motor de voz rechazó el texto.")
                finishConversation()
            }
        } catch (e: Exception) {
            log("error", "No pude reproducir la voz (${e.message}). La respuesta está visible arriba.")
            finishConversation()
        }
    }

    private fun stopEverything(message: Boolean) {
        processing = false
        pausedByUser = true
        hotwordMode = false
        conversationMode = false
        cancelContinuationTimeout()
        recognizer?.cancel()
        if (::tts.isInitialized) tts.stop()
        if (message) speak(
            "De acuerdo. Quedé en pausa. Cuando quieras, volvemos a hablar.",
            source = "Sistema",
            clearPause = false
        )
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS || !::tts.isInitialized) {
            ttsReady = false
            ttsFailed = true
            ttsLangDesc = "motor no disponible"
            log("error", "No se pudo iniciar el motor de voz. Instala 'Google TTS' desde Play Store.")
            return
        }
        val preferred = listOf(Locale("es", "MX"), Locale("es", "US"), Locale("es", "ES"), Locale("es", "CO"), Locale("es", "GT"), Locale("es", "CR"), Locale("es"))
        val chosen = preferred.firstOrNull { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        if (chosen != null) {
            try { tts.language = chosen } catch (_: Exception) { }
            ttsLangDesc = "español (${chosen.displayCountry.ifBlank { chosen.displayLanguage }})"
        } else {
            ttsLangDesc = "idioma del sistema (descarga una voz en español para mejor calidad)"
        }
        try {
            tts.voices?.firstOrNull { v ->
                v.locale.language == "es" && preferred.any { p -> v.locale.country == p.country }
            }?.let { tts.voice = it }
        } catch (_: Exception) { }
        try {
            tts.setSpeechRate(0.93f)
            tts.setPitch(1.04f)
        } catch (_: Exception) { }
        ttsReady = true
        ttsFailed = false
        log("status", "Voz lista: $ttsLangDesc.")
    }

    override fun onDestroy() {
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        destroyRecognizer()
        if (::tts.isInitialized) tts.shutdown()
        tone?.release(); tone = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        // El motor local se conserva en caché: si el servicio reinicia en el
        // mismo proceso, responde al instante sin recargar el modelo.
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
