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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.math.roundToInt

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
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(WayHatService.ACTION_STATUS).apply { addAction(KarbysService.ACTION_UI) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)

        voiceAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        keyConfigured = Prefs.hasApiKey(this)
        keyDraft = ""
        modelDraft = Prefs.model(this)

        setContent {
            var prompt by remember { mutableStateOf("") }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("WAYCORE", style = MaterialTheme.typography.headlineMedium)
                        Text("Versión ${BuildConfig.VERSION_NAME}")
                        Text(if (wayHatConnected) "WayHat conectado" else wayHatMessage)
                        if (!voiceAvailable) {
                            Text("Este teléfono no tiene servicio de reconocimiento de voz: Karbys solo responderá por texto.")
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
                        Text(if (keyConfigured) "Clave de Gemini guardada en este teléfono" else "Falta la clave de Gemini: Karbys solo usará comandos locales")
                        OutlinedTextField(
                            value = keyDraft, onValueChange = { keyDraft = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            label = { Text("Clave de Gemini (AI Studio)") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = modelDraft, onValueChange = { modelDraft = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            label = { Text("Modelo (opcional): ${GeminiClient.models(this).joinToString(" o ")}") },
                            singleLine = true
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { saveSettings() }, modifier = Modifier.weight(1f)) { Text("GUARDAR") }
                            Button(onClick = { send(KarbysService.ACTION_GREETING) }, modifier = Modifier.weight(1f)) { Text("SALUDAR") }
                        }
                    }
                }
            }
        }
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateDeviceInfo()
        keyConfigured = Prefs.hasApiKey(this)
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun saveSettings() {
        Prefs.setApiKey(this, keyDraft.trim())
        Prefs.setModel(this, modelDraft.trim())
        keyConfigured = Prefs.hasApiKey(this)
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
