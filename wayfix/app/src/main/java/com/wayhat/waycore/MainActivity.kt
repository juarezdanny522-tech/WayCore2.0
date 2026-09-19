package com.wayhat.waycore

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.*
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ready by mutableStateOf(false)
    private var paused by mutableStateOf(false)
    private var wayHatConnected by mutableStateOf(false)
    private var wayHatMessage by mutableStateOf("Buscando WayHat…")
    private var right by mutableStateOf(-1)
    private var left by mutableStateOf(-1)
    private var rear by mutableStateOf(-1)
    private var tf by mutableStateOf(-1)
    private var closest by mutableStateOf(-1)
    private var temp by mutableStateOf("sin lectura")
    private var hum by mutableStateOf("sin lectura")
    private var threshold by mutableStateOf(50)
    private var mode by mutableStateOf("SAFE")
    private var buzzer by mutableStateOf(true)
    private var battery by mutableStateOf(0)
    private var locationText by mutableStateOf("Ubicación no disponible")
    private var lastKarbys by mutableStateOf("Karbys todavía no ha dicho nada.")
    private var voiceAvailable by mutableStateOf(true)
    private var keyConfigured by mutableStateOf(false)
    private var keyDraft by mutableStateOf("")
    private var modelDraft by mutableStateOf("")
    private var brainMode by mutableStateOf(Prefs.BRAIN_AUTO)
    private var modelReady by mutableStateOf(false)
    private var modelLabel by mutableStateOf("")
    private var quantLabel by mutableStateOf("")
    private var modelPercent by mutableStateOf(0f)
    private var downloading by mutableStateOf(false)
    private var gpuPref by mutableStateOf(false)
    private var voiceAlertPref by mutableStateOf(true)
    private var deviceNote by mutableStateOf("")
    private var deviceCheck by mutableStateOf("Verificando compatibilidad…")
    private var updateStatus by mutableStateOf("")
    private var updateAvailable by mutableStateOf(false)
    private var latestVersion by mutableStateOf("")
    private var apkDownloadProgress by mutableStateOf(0)
    private var isApkDownloading by mutableStateOf(false)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] == true || has(Manifest.permission.RECORD_AUDIO)
        if (mic) startKarbys() else paused = true
        startWayHat()
        updateDeviceInfo()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WayHatService.ACTION_STATUS -> {
                    intent.getStringExtra("message")?.let { wayHatMessage = it }
                    if (intent.hasExtra("connected")) wayHatConnected = intent.getBooleanExtra("connected", false)
                    intent.getStringExtra("telemetry")?.let { parseTelemetry(it) }
                }
                KarbysService.ACTION_UI -> intent.getStringExtra("message")?.let { lastKarbys = it }
                ModelManager.ACTION_PROGRESS -> {
                    val phase = intent.getStringExtra(ModelManager.EXTRA_PHASE).orEmpty()
                    modelLabel = intent.getStringExtra(ModelManager.EXTRA_TEXT).orEmpty()
                    modelPercent = intent.getIntExtra(ModelManager.EXTRA_PERCENT, 0) / 100f
                    downloading = phase != "listo" && phase != "error"
                    if (phase == "listo" || phase == "error") refreshModelStatus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(WayHatService.ACTION_STATUS).apply {
            addAction(KarbysService.ACTION_UI)
            addAction(ModelManager.ACTION_PROGRESS)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)

        checkDeviceCompatibility()
        voiceAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        keyConfigured = Prefs.hasApiKey(this)
        keyDraft = ""
        modelDraft = Prefs.model(this)
        refreshModelStatus()

        // Auto-sugerir mejor modelo para gama media-alta si aún no tiene uno
        if (Prefs.modelName(this).isBlank()) {
            val best = ModelManager.bestModelForThisPhone(this)
            Prefs.selectQuantization(this, best.fileName, best.url)
            refreshModelStatus()
        }

        setContent {
            var prompt by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            val context = LocalContext.current
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("WAYCORE", style = MaterialTheme.typography.headlineMedium)
                        Text("Versión ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) - Auto-actualizable", style = MaterialTheme.typography.labelSmall)
                        Text(if (wayHatConnected) "WayHat conectado" else wayHatMessage)
                        Text(deviceCheck, style = MaterialTheme.typography.bodySmall)
                        if (!voiceAvailable) {
                            Text("Este teléfono no tiene servicio de reconocimiento de voz: Karbys solo responderá por texto.", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(10.dp))

                        // --- Sección de actualización automática APK ---
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Actualizaciones APK", style = MaterialTheme.typography.titleMedium)
                                Text("Al tocar el APK nuevo se actualiza solo, sin desinstalar. No se descarga modelo dentro del APK.", style = MaterialTheme.typography.labelSmall)
                                if (updateStatus.isNotBlank()) Text(updateStatus, style = MaterialTheme.typography.bodySmall)
                                if (isApkDownloading) {
                                    LinearProgressIndicator(progress = { apkDownloadProgress / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                                    Text("$apkDownloadProgress% descargado")
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    Button(onClick = {
                                        scope.launch {
                                            updateStatus = "Buscando actualización..."
                                            val info = UpdateManager.checkForUpdate(context)
                                            latestVersion = info.latestVersion
                                            if (info.available && info.downloadUrl != null) {
                                                updateAvailable = true
                                                updateStatus = "¡Nueva versión ${info.latestVersion} disponible! Actual: ${info.currentVersion}. Toca DESCARGAR y luego INSTALAR, se actualizará solo."
                                            } else if (info.error != null) {
                                                updateStatus = info.error + " Versión actual: ${info.currentVersion}"
                                            } else {
                                                updateStatus = "Ya tienes la última versión (${info.currentVersion}). Al tocar el APK nuevo, se actualiza solo sin desinstalar."
                                            }
                                        }
                                    }) { Text("BUSCAR APK") }

                                    if (updateAvailable) {
                                        Button(onClick = {
                                            scope.launch {
                                                val info = UpdateManager.checkForUpdate(context)
                                                val url = info.downloadUrl ?: return@launch
                                                isApkDownloading = true
                                                apkDownloadProgress = 0
                                                updateStatus = "Descargando ${info.latestVersion}..."
                                                val result = UpdateManager.downloadAndInstall(context, url) { prog -> apkDownloadProgress = prog }
                                                updateStatus = result
                                                isApkDownloading = false
                                            }
                                        }) { Text("DESCARGAR") }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { if (!ready) requestPermissionsIfNeeded() else send(KarbysService.ACTION_LISTEN) },
                            modifier = Modifier.size(230.dp).semantics { contentDescription = "Hablar con Karbys" }
                        ) { Text(if (ready) "HABLAR" else "KARBYS") }
                        Text(lastKarbys)

                        OutlinedTextField(
                            value = prompt, onValueChange = { prompt = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                            label = { Text("Escribe una pregunta para Karbys") }
                        )
                        Button(onClick = { if (prompt.isNotBlank()) { sendText(prompt.trim()); prompt = "" } }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("ENVIAR")
                        }

                        HorizontalDivider(Modifier.padding(vertical = 18.dp))
                        Text("WAYHAT", style = MaterialTheme.typography.titleLarge)
                        Text("Modo: ${if (mode == "SAFE") "Seguro" else "Charla"}")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { mode = "SAFE"; sendConfig() }, Modifier.weight(1f)) { Text("SEGURO") }
                            Button(onClick = { mode = "CHAT"; sendConfig() }, Modifier.weight(1f)) { Text("CHARLA") }
                        }
                        Text("Sensibilidad: $threshold cm")
                        Slider(value = threshold.toFloat(), onValueChange = { threshold = (it / 5).roundToInt() * 5 }, valueRange = 20f..150f, onValueChangeFinished = { sendConfig() })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Avisos sonoros")
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = buzzer, onCheckedChange = { buzzer = it; sendConfig() })
                        }

                        Text("Frente: ${cm(tf)}   Derecha: ${cm(right)}   Izquierda: ${cm(left)}   Atrás: ${cm(rear)}")
                        Text("Más cercano: ${cm(closest)}")
                        Text("Temperatura: $temp   Humedad: $hum")
                        Text("Batería: $battery%")
                        Text("GPS: $locationText")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { startWayHat() }) { Text("RECONECTAR WAYHAT") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { sendHardware("BUZZER_TEST") }) { Text("PROBAR BUZZER") }
                            Button(onClick = { sendHardware("SENSORS") }) { Text("ACTUALIZAR") }
                        }
                        if (paused) Button(onClick = { requestPermissionsIfNeeded() }) { Text("ACTIVAR KARBYS") }

                        HorizontalDivider(Modifier.padding(vertical = 18.dp))
                        Text("AJUSTES DE KARBYS", style = MaterialTheme.typography.titleLarge)
                        Text(if (keyConfigured) "Clave de Gemini guardada (modelos arreglados: 2.0-flash, 1.5-flash)" else "Falta clave Gemini: usa modo LOCAL o guarda clave. Modelos ya compatibles con gama media.")
                        OutlinedTextField(
                            value = keyDraft, onValueChange = { keyDraft = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            label = { Text("Clave de Gemini (AI Studio)") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = modelDraft, onValueChange = { modelDraft = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            label = { Text("Modelo Gemini (opcional): ${GeminiClient.models(this@MainActivity).joinToString()}") },
                            singleLine = true
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { saveSettings() }, modifier = Modifier.weight(1f)) { Text("GUARDAR") }
                            Button(onClick = { send(KarbysService.ACTION_GREETING) }, modifier = Modifier.weight(1f)) { Text("SALUDAR") }
                        }

                        HorizontalDivider(Modifier.padding(vertical = 18.dp))
                        Text("CEREBRO DE KARBYS - LOCAL COMPATIBLE", style = MaterialTheme.typography.titleLarge)
                        Text("Para gama media-alta recomendamos Q3 (924 MB) o Q2 (752 MB). Q4_K_M solo si tienes 6GB+.", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { pickBrain(Prefs.BRAIN_LOCAL) }, modifier = Modifier.weight(1f)) { Text("LOCAL") }
                            Button(onClick = { pickBrain(Prefs.BRAIN_AUTO) }, modifier = Modifier.weight(1f)) { Text("AUTO") }
                            Button(onClick = { pickBrain(Prefs.BRAIN_CLOUD) }, modifier = Modifier.weight(1f)) { Text("NUBE") }
                        }
                        Text("Modo: ${brainLabel(brainMode)}")
                        Text(quantLabel)
                        Text(modelStatus())
                        if (downloading) {
                            LinearProgressIndicator(
                                progress = { modelPercent },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            )
                            if (modelLabel.isNotBlank()) Text(modelLabel)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { toggleDownload() }, modifier = Modifier.weight(1f)) {
                                Text(if (downloading) "CANCELAR" else "DESCARGAR IA")
                            }
                            Button(onClick = { deleteModel() }, modifier = Modifier.weight(1f)) { Text("BORRAR") }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { nextQuant() }, modifier = Modifier.weight(1f)) { Text("OTRA VERSIÓN") }
                            Button(onClick = {
                                val best = ModelManager.bestModelForThisPhone(context)
                                Prefs.selectQuantization(context, best.fileName, best.url)
                                refreshModelStatus()
                                deviceNote = "Sugerido para tu gama media-alta: ${best.fileName} (${best.megabytes} MB)"
                            }, modifier = Modifier.weight(1f)) { Text("AUTO GAMA MEDIA") }
                        }
                        Button(onClick = { sendText("preséntate en una frase corta") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text("PROBAR EL CEREBRO")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Usar GPU (puede fallar en gama media, desactívalo si falla)")
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = gpuPref, onCheckedChange = { gpuPref = it; Prefs.setUseGpu(this@MainActivity, it) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Avisarme los obstáculos con la voz")
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = voiceAlertPref,
                                onCheckedChange = { voiceAlertPref = it; Prefs.setVoiceAlerts(this@MainActivity, it) }
                            )
                        }
                        Text("Teléfono: ${LocalBrain.deviceSummary(this@MainActivity)}", style = MaterialTheme.typography.bodySmall)
                        Text("Compatibilidad: $deviceCheck", style = MaterialTheme.typography.bodySmall)
                        if (deviceNote.isNotBlank()) {
                            Card(Modifier.fillMaxWidth().padding(top = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Text(deviceNote, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        requestPermissionsIfNeeded()

        // Auto-check APK actualización silencioso
        uiScope.launch(Dispatchers.IO) {
            try {
                val info = UpdateManager.checkForUpdate(this@MainActivity)
                launch(Dispatchers.Main) {
                    if (info.available) {
                        updateAvailable = true
                        latestVersion = info.latestVersion
                        updateStatus = "Nueva APK ${info.latestVersion} disponible."
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun checkDeviceCompatibility() {
        val ram = ModelManager.totalRamMegabytes(this)
        val is64 = ModelManager.hasCpuForEngine(this)
        val cores = Runtime.getRuntime().availableProcessors()
        val sb = StringBuilder()
        sb.append("Android ${android.os.Build.VERSION.SDK_INT}, $cores núcleos, $ram MB RAM, ${if (is64) "64-bit ✓" else "32-bit ✗"}. ")
        if (!is64) sb.append("Motor local no compatible, usa NUBE (Gemini ya arreglado). ")
        else if (ram >= 5500) sb.append("Gama alta, usa Q4_K_M. ")
        else if (ram >= 4000) sb.append("Gama media-alta ideal para Q3 (recomendado). ")
        else if (ram >= 3000) sb.append("Gama media, usa Q2. ")
        else sb.append("Gama baja, usa 0.5B o NUBE. ")
        sb.append("APK se actualiza con un toque.")
        deviceCheck = sb.toString()
    }

    override fun onResume() {
        super.onResume()
        updateDeviceInfo()
        keyConfigured = Prefs.hasApiKey(this)
        refreshModelStatus()
    }

    override fun onDestroy() {
        uiScope.cancel()
        try { unregisterReceiver(receiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun saveSettings() {
        Prefs.setApiKey(this, keyDraft.trim())
        Prefs.setModel(this, modelDraft.trim())
        keyConfigured = Prefs.hasApiKey(this)
        deviceNote = ""
    }

    private fun pickBrain(mode: String) {
        Prefs.setBrainMode(this, mode)
        brainMode = mode
    }

    private fun brainLabel(mode: String): String = when (mode) {
        Prefs.BRAIN_LOCAL -> "solo el modelo del teléfono, sin Internet"
        Prefs.BRAIN_CLOUD -> "solo Gemini por Internet (modelos arreglados)"
        else -> "AUTO: local si está descargado, si no nube"
    }

    private fun modelStatus(): String {
        val file = ModelManager.modelFile(this)
        val spec = ModelManager.specFor(this)
        return when {
            modelReady -> "✓ Está en el teléfono: ${ModelManager.megabytes(file.length())} MB. Karbys piensa sin Internet."
            downloading -> "Descargando ${spec.fileName}, no cierres la app."
            else -> "Falta descargar: ${spec.fileName} (${spec.megabytes} MB). Hay ${ModelManager.freeMegabytes(this)} MB libres. RAM: ${ModelManager.totalRamMegabytes(this)} MB."
        }
    }

    private fun toggleDownload() {
        if (ModelTransferService.isBusy()) {
            ModelTransferService.cancel(this)
            downloading = false
            return
        }
        // Para gama media-alta permitimos aunque RAM esté justa, solo bloqueamos 32-bit y <2.5GB
        if (!ModelManager.hasCpuForEngine(this)) {
            deviceNote = ModelManager.reasonItDoesNotFit(this)
            return
        }
        val ram = ModelManager.totalRamMegabytes(this)
        if (ram in 1..2499) {
            deviceNote = ModelManager.reasonItDoesNotFit(this)
            return
        }
        if (ram in 2500..3499 && ModelManager.specFor(this).minRamMegabytes > 3200) {
            deviceNote = "Tu teléfono tiene $ram MB y el modelo pide ${ModelManager.specFor(this).minRamMegabytes} MB. Te recomiendo tocar AUTO GAMA MEDIA para elegir Q2 o 0.5B. Si quieres probar igual, toca de nuevo DESCARGAR IA."
            // Permitir segundo intento
            if (!deviceNote.contains("segundo")) {
                // No bloquear, solo avisar
            }
        }
        deviceNote = ""
        ModelTransferService.start(this)
        downloading = true
    }

    private fun deleteModel() {
        uiScope.launch {
            ModelManager.deleteModel(this@MainActivity)
            downloading = false
            modelPercent = 0f
            refreshModelStatus()
        }
    }

    private fun nextQuant() {
        val specs = ModelManager.SPECS
        val index = specs.indexOfFirst { it.fileName == ModelManager.specFor(this).fileName }
        val next = specs[((if (index < 0) 0 else index) + 1) % specs.size]
        Prefs.selectQuantization(this, next.fileName, next.url)
        refreshModelStatus()
    }

    private fun refreshModelStatus() {
        val spec = ModelManager.specFor(this)
        modelReady = ModelManager.isReady(this)
        quantLabel = spec.label
        brainMode = Prefs.brainMode(this)
        gpuPref = Prefs.useGpu(this)
        voiceAlertPref = Prefs.voiceAlerts(this)
        downloading = ModelTransferService.isBusy()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (!has(Manifest.permission.RECORD_AUDIO)) needed += Manifest.permission.RECORD_AUDIO
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) needed += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_SCAN)) needed += Manifest.permission.BLUETOOTH_SCAN
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isEmpty()) { startKarbys(); startWayHat(); updateDeviceInfo() } else permissions.launch(needed.toTypedArray())
    }

    private fun startKarbys() {
        ready = true; paused = false
        ContextCompat.startForegroundService(this, Intent(this, KarbysService::class.java).setAction(KarbysService.ACTION_GREETING))
    }

    private fun startWayHat() {
        ContextCompat.startForegroundService(this, Intent(this, WayHatService::class.java).setAction(WayHatService.ACTION_START))
    }

    private fun send(action: String) = ContextCompat.startForegroundService(this, Intent(this, KarbysService::class.java).setAction(action))
    private fun sendText(text: String) = ContextCompat.startForegroundService(this, Intent(this, KarbysService::class.java).setAction(KarbysService.ACTION_TEXT).putExtra("text", text))

    private fun sendConfig() {
        sendWayHat(JSONObject().put("type", "config").put("threshold", threshold).put("mode", mode).put("buzzer", buzzer).toString())
    }
    private fun sendHardware(name: String) {
        sendWayHat(JSONObject().put("type", "command").put("name", name).toString())
    }
    private fun sendWayHat(json: String) {
        ContextCompat.startForegroundService(this, Intent(this, WayHatService::class.java).setAction(WayHatService.ACTION_COMMAND).putExtra(WayHatService.EXTRA_JSON, json))
    }

    private fun parseTelemetry(line: String) {
        try {
            val o = JSONObject(line)
            right = o.optInt("right", right); left = o.optInt("left", left); rear = o.optInt("rear", rear)
            tf = o.optInt("tf", tf); closest = o.optInt("closest", closest)
            threshold = o.optInt("threshold", threshold).coerceIn(20, 150)
            mode = o.optString("mode", mode)
            buzzer = o.optBoolean("buzzer", buzzer)
            (o.opt("temp") as? Number)?.let { temp = "$it °C" }
            (o.opt("hum") as? Number)?.let { hum = "$it %" }
        } catch (_: Exception) { }
    }

    private fun updateDeviceInfo() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        battery = if (level >= 0) level * 100 / scale else 0
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val loc = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }.maxByOrNull { it.time }
        if (loc != null) locationText = "%.5f, %.5f".format(loc.latitude, loc.longitude)
    }

    private fun cm(v: Int) = if (v > 0) "$v cm" else "—"
    private fun has(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
