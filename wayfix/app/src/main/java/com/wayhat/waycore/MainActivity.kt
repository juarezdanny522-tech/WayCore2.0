package com.wayhat.waycore

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

data class KarbysLogLine(val kind: String, val text: String, val source: String, val time: String)

class MainActivity : ComponentActivity() {
    private var ready by mutableStateOf(false)
    private var paused by mutableStateOf(false)
    private var wayHatConnected by mutableStateOf(false)
    private var wayHatMessage by mutableStateOf("Buscando WayHat…")
    private var right by mutableStateOf(-1)
    private var left by mutableStateOf(-1)
    private var rear by mutableStateOf(-1)
    private var tf by mutableStateOf(-1)
    private var closest by mutableStateOf(-1)
    private var threshold by mutableStateOf(50)
    private var mode by mutableStateOf("SAFE")
    private var buzzer by mutableStateOf(true)
    private var battery by mutableStateOf(0)
    private var locationText by mutableStateOf("Ubicación no disponible")

    // Karbys: registro visible, modo de motor y diagnóstico.
    private val karbysLog = mutableStateListOf<KarbysLogLine>()
    private val diagLines = mutableStateListOf<String>()
    private var engineMode by mutableStateOf(LocalModelManager.MODE_AUTO)
    private var modelStatus by mutableStateOf("")
    private var modelProgress by mutableStateOf(-1) // -1 quieto, 0-100 descargando
    private var modelDetail by mutableStateOf("")
    private var downloading by mutableStateOf(false)
    private var importing by mutableStateOf(false)

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
                KarbysService.ACTION_LOG -> {
                    val kind = intent.getStringExtra(KarbysService.EXTRA_KIND).orEmpty()
                    val text = intent.getStringExtra(KarbysService.EXTRA_TEXT).orEmpty()
                    val source = intent.getStringExtra(KarbysService.EXTRA_SOURCE).orEmpty()
                    if (text.isNotBlank()) {
                        karbysLog.add(KarbysLogLine(kind.ifBlank { "status" }, text, source, timeFmt.format(Date())))
                        while (karbysLog.size > 60) karbysLog.removeAt(0)
                    }
                }
                ModelDownloadService.ACTION_MODEL_EVENT -> {
                    val ev = intent.getStringExtra(ModelDownloadService.EXTRA_EVENT).orEmpty()
                    val pct = intent.getIntExtra(ModelDownloadService.EXTRA_PROGRESS, -1)
                    val msg = intent.getStringExtra(ModelDownloadService.EXTRA_MESSAGE).orEmpty()
                    when (ev) {
                        ModelDownloadService.EVENT_STARTED,
                        ModelDownloadService.EVENT_PROGRESS -> {
                            downloading = true
                            modelProgress = pct
                            modelDetail = msg
                        }
                        ModelDownloadService.EVENT_DONE,
                        ModelDownloadService.EVENT_ERROR,
                        ModelDownloadService.EVENT_CANCELLED -> {
                            downloading = false
                            modelProgress = -1
                            modelDetail = msg
                            refreshModelStatus()
                            if (ev == ModelDownloadService.EVENT_DONE) {
                                sendKarbys(KarbysService.ACTION_RELOAD_MODEL)
                            }
                        }
                    }
                }
                KarbysService.ACTION_DIAG -> {
                    diagLines.clear()
                    intent.getStringArrayListExtra(KarbysService.EXTRA_DIAG_LINES)?.let { diagLines.addAll(it) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engineMode = LocalModelManager.getMode(this)
        refreshModelStatus()
        val filter = IntentFilter().apply {
            addAction(WayHatService.ACTION_STATUS)
            addAction(KarbysService.ACTION_LOG)
            addAction(ModelDownloadService.ACTION_MODEL_EVENT)
            addAction(KarbysService.ACTION_DIAG)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var prompt by remember { mutableStateOf("") }
                    var customUrl by remember { mutableStateOf(LocalModelManager.getCustomUrl(this)) }
                    var hfToken by remember { mutableStateOf(LocalModelManager.getHfToken(this)) }
                    var showToken by remember { mutableStateOf(false) }
                    var modelChoice by remember { mutableStateOf(LocalModelManager.getSelectedId(this)) }
                    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                        if (uri != null) importModel(uri)
                    }
                    val pageScroll = rememberScrollState()
                    val logScroll = rememberScrollState()
                    LaunchedEffect(karbysLog.size) {
                        try { logScroll.scrollTo(logScroll.maxValue) } catch (_: Exception) { }
                    }

                    Column(
                        Modifier.fillMaxSize().verticalScroll(pageScroll).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("WAYCORE", style = MaterialTheme.typography.headlineMedium)
                        Text(if (wayHatConnected) "WayHat conectado" else wayHatMessage)
                        Spacer(Modifier.height(12.dp))

                        // ---------- Motor de IA ----------
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("Motor de Karbys", style = MaterialTheme.typography.titleMedium)
                                Text("Automático usa la IA local sin internet y Gemini como respaldo.", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { applyEngineMode(LocalModelManager.MODE_AUTO) },
                                        modifier = Modifier.weight(1f).semantics { contentDescription = "Modo automático" }
                                    ) { Text(if (engineMode == LocalModelManager.MODE_AUTO) "● Auto" else "Auto") }
                                    Button(
                                        onClick = { applyEngineMode(LocalModelManager.MODE_LOCAL) },
                                        modifier = Modifier.weight(1f).semantics { contentDescription = "Solo IA local" }
                                    ) { Text(if (engineMode == LocalModelManager.MODE_LOCAL) "● Local" else "Local") }
                                    Button(
                                        onClick = { applyEngineMode(LocalModelManager.MODE_GEMINI) },
                                        modifier = Modifier.weight(1f).semantics { contentDescription = "Solo Gemini" }
                                    ) { Text(if (engineMode == LocalModelManager.MODE_GEMINI) "● Nube" else "Nube") }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // ---------- Karbys ----------
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("KARBYS", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { if (!ready) requestPermissionsIfNeeded() else sendKarbys(KarbysService.ACTION_LISTEN) },
                                    modifier = Modifier.size(230.dp, 64.dp).semantics { contentDescription = "Hablar con Karbys" }
                                ) { Text(if (ready) "HABLAR" else "KARBYS") }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { sendKarbys(KarbysService.ACTION_TEST_VOICE) }) { Text("PROBAR VOZ") }
                                    Button(onClick = { sendKarbys(KarbysService.ACTION_DIAGNOSE) }) { Text("DIAGNÓSTICO") }
                                }
                                OutlinedTextField(
                                    value = prompt, onValueChange = { prompt = it },
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                    label = { Text("Escribe una pregunta para Karbys") }
                                )
                                Button(
                                    onClick = { if (prompt.isNotBlank()) { sendText(prompt.trim()); prompt = "" } },
                                    modifier = Modifier.padding(top = 8.dp)
                                ) { Text("ENVIAR") }
                                Spacer(Modifier.height(8.dp))
                                Text("Registro (todo queda aquí aunque falle el audio):", style = MaterialTheme.typography.bodySmall)
                                Column(
                                    Modifier.fillMaxWidth().height(220.dp).verticalScroll(logScroll).padding(top = 4.dp)
                                ) {
                                    if (karbysLog.isEmpty()) Text("Aún no hay mensajes. Prueba con PROBAR VOZ.", style = MaterialTheme.typography.bodySmall)
                                    karbysLog.forEach { l ->
                                        val prefix = when (l.kind) {
                                            "user" -> "Tú:"
                                            "karbys" -> "Karbys:"
                                            "error" -> "Aviso:"
                                            else -> "•"
                                        }
                                        val src = if (l.source.isNotBlank()) " [${l.source}]" else ""
                                        Text(
                                            "${l.time} $prefix$src ${l.text}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (l.kind == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // ---------- IA local ----------
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("IA LOCAL (sin internet)", style = MaterialTheme.typography.titleMedium)
                                Text(modelStatus, style = MaterialTheme.typography.bodySmall)
                                if (modelDetail.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(modelDetail, style = MaterialTheme.typography.bodySmall)
                                }
                                if (modelProgress >= 0) {
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(progress = modelProgress / 100f, modifier = Modifier.fillMaxWidth())
                                }
                                if (importing) {
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("Modelo:", style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LocalModelManager.CATALOG.forEach { spec ->
                                        Button(
                                            onClick = {
                                                modelChoice = spec.id
                                                LocalModelManager.setSelectedId(this@MainActivity, spec.id)
                                                customUrl = ""
                                                LocalModelManager.setCustomUrl(this@MainActivity, "")
                                                refreshModelStatus()
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text(if (modelChoice == spec.id) "● ${spec.shortName}" else spec.shortName) }
                                    }
                                }
                                OutlinedTextField(
                                    value = customUrl,
                                    onValueChange = {
                                        customUrl = it
                                        LocalModelManager.setCustomUrl(this@MainActivity, it)
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    label = { Text("URL personalizada de .task (opcional)") },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = hfToken,
                                    onValueChange = {
                                        hfToken = it
                                        LocalModelManager.setHfToken(this@MainActivity, it)
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    label = { Text("Token de HuggingFace (para Gemma)") },
                                    singleLine = true,
                                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation()
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("El modelo oficial pide token gratis. Ver guía IA_LOCAL.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Ocultar" else "Ver") }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (downloading) {
                                        Button(onClick = { cancelDownload() }, modifier = Modifier.weight(1f)) { Text("CANCELAR") }
                                    } else {
                                        Button(onClick = { startDownload() }, modifier = Modifier.weight(1f)) { Text("DESCARGAR") }
                                    }
                                    Button(
                                        onClick = { try { importLauncher.launch(arrayOf("*/*")) } catch (_: Exception) { } },
                                        modifier = Modifier.weight(1f),
                                        enabled = !downloading && !importing
                                    ) { Text("IMPORTAR") }
                                }
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { sendKarbys(KarbysService.ACTION_RELOAD_MODEL) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("RECARGAR") }
                                    Button(
                                        onClick = {
                                            LocalModelManager.deleteModels(this@MainActivity)
                                            lifecycleScope.launch(Dispatchers.IO) { LocalBrain.release() }
                                            refreshModelStatus()
                                            modelDetail = "Modelo borrado."
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("BORRAR") }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // ---------- WayHat ----------
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("WAYHAT", style = MaterialTheme.typography.titleLarge)
                                Text("Modo: ${if (mode == "SAFE") "Seguro" else "Charla"}")
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { mode = "SAFE"; sendConfig() }, Modifier.weight(1f)) { Text("SEGURO") }
                                    Button(onClick = { mode = "CHAT"; sendConfig() }, Modifier.weight(1f)) { Text("CHARLA") }
                                }
                                Text("Sensibilidad: $threshold cm")
                                Slider(value = threshold.toFloat(), onValueChange = { threshold = (it / 5).roundToInt() * 5 }, valueRange = 20f..100f, onValueChangeFinished = { sendConfig() })
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Avisos sonoros")
                                    Spacer(Modifier.width(12.dp))
                                    Switch(checked = buzzer, onCheckedChange = { buzzer = it; sendConfig() })
                                }
                                Text("Derecha: ${cm(right)}   Izquierda: ${cm(left)}   Atrás: ${cm(rear)}")
                                Text("TF-Luna: ${cm(tf)}   Más cercano: ${cm(closest)}")
                                Text("Batería: $battery%")
                                Text("GPS: $locationText")
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { startWayHat() }) { Text("RECONECTAR WAYHAT") }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { sendHardware("BUZZER_TEST") }) { Text("PROBAR BUZZER") }
                                    Button(onClick = { sendHardware("SENSORS") }) { Text("ACTUALIZAR") }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // ---------- Diagnóstico ----------
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("DIAGNÓSTICO", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { sendKarbys(KarbysService.ACTION_DIAGNOSE) }) { Text("EJECUTAR") }
                                    Button(onClick = { openTtsSettings() }) { Text("AJUSTES DE VOZ") }
                                }
                                Spacer(Modifier.height(6.dp))
                                if (diagLines.isEmpty()) Text("Pulsa EJECUTAR para revisar voz, IA local, red y sensores.", style = MaterialTheme.typography.bodySmall)
                                diagLines.forEach {
                                    Text(
                                        it, style = MaterialTheme.typography.bodySmall,
                                        color = if (it.startsWith("FALLO")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        if (paused) Button(onClick = { requestPermissionsIfNeeded() }, modifier = Modifier.padding(top = 12.dp)) { Text("ACTIVAR KARBYS") }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
        requestPermissionsIfNeeded()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
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

    private fun applyEngineMode(m: String) {
        engineMode = m
        LocalModelManager.setMode(this, m)
        ContextCompat.startForegroundService(
            this,
            Intent(this, KarbysService::class.java).setAction(KarbysService.ACTION_SET_MODE)
                .putExtra(KarbysService.EXTRA_MODE, m)
        )
    }

    private fun refreshModelStatus() {
        modelStatus = LocalModelManager.modelStatusText(this)
    }

    private fun startDownload() {
        val spec = LocalModelManager.effectiveSpec(this)
        val token = LocalModelManager.getHfToken(this)
        modelDetail = "Iniciando descarga de ${spec.fileName}…"
        ContextCompat.startForegroundService(this, ModelDownloadService.downloadIntent(this, spec, token))
    }

    private fun cancelDownload() {
        startService(Intent(this, ModelDownloadService::class.java).setAction(ModelDownloadService.ACTION_CANCEL))
    }

    private fun importModel(uri: Uri) {
        importing = true
        modelDetail = "Importando archivo…"
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                LocalModelManager.importFromUri(this@MainActivity, uri) { _, detail ->
                    runOnUiThread { modelDetail = detail }
                }
            }
            importing = false
            res.onSuccess {
                refreshModelStatus()
                modelDetail = "Modelo importado: ${it.name}."
                sendKarbys(KarbysService.ACTION_RELOAD_MODEL)
            }.onFailure { e ->
                refreshModelStatus()
                modelDetail = e.message ?: "Importación fallida."
            }
        }
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { }
        }
    }

    private fun sendKarbys(action: String) = ContextCompat.startForegroundService(this, Intent(this, KarbysService::class.java).setAction(action))
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
            threshold = o.optInt("threshold", threshold)
            mode = o.optString("mode", mode)
            buzzer = o.optBoolean("buzzer", buzzer)
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
