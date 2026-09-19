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
        const val CHANNEL = "karbys_assistant"
        const val NOTIFICATION_ID = 2401
        private const val CONTINUATION_SILENCE_MS = 4500L
        private const val CONTINUATION_WINDOW_MS = 6000L
        private const val COMMAND_RETRY_DELAY_MS = 180L
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
                if (utteranceId == "karbys-answer") main.post { beginContinuationWindow() }
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
        // Verificación amplia de disponibilidad para gama media
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                // En algunos dispositivos chinos, isRecognitionAvailable devuelve false pero igual funciona con Google app
                // Intentamos crear de todas formas si Google está instalado
                val pm = packageManager
                val hasGoogle = try { pm.getPackageInfo("com.google.android.googlequicksearchbox", 0); true } catch (_: Exception) { false }
                if (!hasGoogle) return null
            }
        } catch (_: Exception) {
            // Si falla el check, intentamos igual
        }

        destroyRecognizer()
        val generation = recognizerGeneration
        val r = try {
            // Intentar con contexto de aplicación para evitar leaks en gama media
            SpeechRecognizer.createSpeechRecognizer(applicationContext)
        } catch (_: Exception) {
            try { SpeechRecognizer.createSpeechRecognizer(this) } catch (_: Exception) { return null }
        }
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
        // Intentar es-MX primero, pero con fallback a es-ES y default del sistema para compatibilidad
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX")
        putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("es-MX", "es-ES", "es-US", "es"))
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
        // Preferir offline si está disponible para gama media sin internet rápido
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
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
        continuationDeadline = 0L
        val clean = text.trim()
        scope.launch {
            val direct = executeLocalCommand(clean)
            val answer = direct ?: GeminiClient.ask(clean, memory.toList(), buildDeviceContext())
            memory.add(ConversationTurn(clean, answer))
            while (memory.size > 4) memory.removeAt(0)
            withContext(Dispatchers.Main) {
                processing = false
                speak(answer)
            }
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
            n.contains("cancela todas las alarmas") || n.contains("borra todos los recordatorios") -> {
                ReminderStore.clear(this)
                "Listo. Eliminé tus recordatorios programados."
            }
            else -> null
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

    private fun speak(text: String) {
        pausedByUser = false
        if (!::tts.isInitialized) {
            // Si TTS no está listo, igual intentar iniciar hotword para no bloquear
            try { startHotword() } catch (_: Exception) {}
            return
        }
        hotwordMode = false
        conversationMode = true
        val spoken = text.replace("WayCore", "guaycor", ignoreCase = true)
            .replace("WayHat", "guayjat", ignoreCase = true)
            .replace("WayCorp", "guaycorp", ignoreCase = true)
        beepReady()
        main.postDelayed({
            try {
                routeToHeadsetIfPossible()
                // Verificar que TTS no esté hablando ya para evitar corte en gama media
                if (::tts.isInitialized) {
                    val result = tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "karbys-answer")
                    if (result == TextToSpeech.ERROR) {
                        // Fallback: reintentar con idioma default
                        try {
                            tts.language = Locale.getDefault()
                            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "karbys-answer")
                        } catch (_: Exception) {
                            // Si todo falla, al menos volver a escuchar
                            beginContinuationWindow()
                        }
                    }
                }
            } catch (e: Exception) {
                // En algunos dispositivos con poca RAM, TTS puede lanzar excepción
                try { beginContinuationWindow() } catch (_: Exception) {}
            }
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
        if (status != TextToSpeech.SUCCESS) {
            // Intentar reinicializar TTS con motor por defecto si falla
            try {
                tts = TextToSpeech(this, null)
            } catch (_: Exception) {}
            return
        }
        try {
            // Lista amplia para gama media-alta: cualquier español sirve
            val preferred = listOf(
                Locale("es", "MX"), Locale("es", "US"), Locale("es", "ES"),
                Locale("es", "CO"), Locale("es", "GT"), Locale("es", "CR"),
                Locale("es", "AR"), Locale("es", "CL"), Locale("es", ""),
                Locale("es"), Locale.getDefault(), Locale.US
            )

            var languageSet = false
            for (loc in preferred) {
                try {
                    val avail = tts.isLanguageAvailable(loc)
                    if (avail >= TextToSpeech.LANG_AVAILABLE) {
                        tts.language = loc
                        languageSet = true
                        break
                    }
                } catch (_: Exception) { continue }
            }

            // Si no hay español, usar el default del sistema pero no fallar
            if (!languageSet) {
                try { tts.language = Locale.getDefault() } catch (_: Exception) {}
            }

            // Buscar voz española compatible sin exigir país exacto
            try {
                val voices = tts.voices
                val spanishVoice = voices?.firstOrNull { v ->
                    v.locale.language.equals("es", true) && !v.isNetworkConnectionRequired
                } ?: voices?.firstOrNull { v -> v.locale.language.equals("es", true) }
                ?: voices?.firstOrNull { v -> v.locale.language.equals("en", true) }

                spanishVoice?.let { tts.voice = it }
            } catch (_: Exception) {
                // Algunos fabricantes (Xiaomi, Huawei) lanzan excepción en tts.voices
            }

            tts.setSpeechRate(0.93f)
            tts.setPitch(1.04f)
        } catch (e: Exception) {
            // Nunca dejar que TTS crashee el servicio en gama media
            try {
                tts.language = Locale("es", "ES")
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
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

