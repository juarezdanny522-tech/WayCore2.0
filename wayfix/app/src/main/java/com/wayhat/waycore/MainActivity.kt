package com.wayhat.waycore

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.*
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
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
    private var threshold by mutableStateOf(50)
    private var mode by mutableStateOf("SAFE")
    private var buzzer by mutableStateOf(true)
    private var battery by mutableStateOf(0)
    private var locationText by mutableStateOf("Ubicación no disponible")
    private var deviceCheck by mutableStateOf("Verificando compatibilidad…")
    private var updateStatus by mutableStateOf("")
    private var updateAvailable by mutableStateOf(false)
    private var latestVersion by mutableStateOf("")
    private var downloadProgress by mutableStateOf(0)
    private var isDownloading by mutableStateOf(false)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] == true || has(Manifest.permission.RECORD_AUDIO)
        if (mic) startKarbys() else paused = true
        startWayHat()
        updateDeviceInfo()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WayHatService.ACTION_STATUS) return
            intent.getStringExtra("message")?.let { wayHatMessage = it }
            if (intent.hasExtra("connected")) wayHatConnected = intent.getBooleanExtra("connected", false)
            intent.getStringExtra("telemetry")?.let { parseTelemetry(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(WayHatService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)

        // Verificación de compatibilidad inmediata para gama media-alta
        checkDeviceCompatibility()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var prompt by remember { mutableStateOf("") }
                    val scope = rememberCoroutineScope()
                    val context = LocalContext.current

                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("WAYCORE", style = MaterialTheme.typography.headlineMedium)
                        Text(if (wayHatConnected) "WayHat conectado" else wayHatMessage)
                        Spacer(Modifier.height(6.dp))
                        Text(deviceCheck, style = MaterialTheme.typography.bodySmall)
                        Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) - Auto-actualizable", style = MaterialTheme.typography.labelSmall)

                        Spacer(Modifier.height(10.dp))

                        // --- Sección de actualización automática ---
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Actualizaciones", style = MaterialTheme.typography.titleMedium)
                                if (updateStatus.isNotBlank()) Text(updateStatus, style = MaterialTheme.typography.bodySmall)
                                if (isDownloading) {
                                    LinearProgressIndicator(progress = { downloadProgress / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                                    Text("$downloadProgress% descargado")
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    Button(onClick = {
                                        scope.launch {
                                            updateStatus = "Buscando actualización..."
                                            val info = UpdateManager.checkForUpdate(context)
                                            latestVersion = info.latestVersion
                                            if (info.available && info.downloadUrl != null) {
                                                updateAvailable = true
                                                updateStatus = "¡Nueva versión ${info.latestVersion} disponible! Actual: ${info.currentVersion}. Toca DESCARGAR y luego INSTALAR, se actualizará solo sin desinstalar."
                                            } else if (info.error != null) {
                                                updateStatus = info.error + " Versión actual: ${info.currentVersion}"
                                                updateAvailable = false
                                            } else {
                                                updateStatus = "Ya tienes la última versión (${info.currentVersion}). Al tocar el APK nuevo, se actualiza solo."
                                                updateAvailable = false
                                            }
                                        }
                                    }) { Text("BUSCAR") }

                                    if (updateAvailable) {
                                        Button(onClick = {
                                            scope.launch {
                                                val info = UpdateManager.checkForUpdate(context)
                                                val url = info.downloadUrl ?: return@launch
                                                isDownloading = true
                                                downloadProgress = 0
                                                updateStatus = "Descargando ${info.latestVersion}..."
                                                val result = UpdateManager.downloadAndInstall(context, url) { prog -> downloadProgress = prog }
                                                updateStatus = result
                                                isDownloading = false
                                            }
                                        }) { Text("DESCARGAR E INSTALAR") }
                                    }
                                }
                                Text(
                                    "Nota: No necesitas desinstalar. Solo toca el APK y elige Actualizar. No se descarga ningún modelo pesado, Gemini está en la nube.",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { if (!ready) requestPermissionsIfNeeded() else send(KarbysService.ACTION_LISTEN) },
                            modifier = Modifier.size(230.dp).semantics { contentDescription = "Hablar con Karbys" }
                        ) { Text(if (ready) "HABLAR" else "KARBYS") }

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

                        Text("Derecha: ${cm(right)}   Izquierda: ${cm(left)}   Atrás: ${cm(rear)}")
                        Text("TF-Luna: ${cm(tf)}   Más cercano: ${cm(closest)}")
                        Text("Batería: $battery%")
                        Text("GPS: $locationText")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { startWayHat() }) { Text("RECONECTAR WAYHAT") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { sendHardware("BUZZER_TEST") }) { Text("PROBAR BUZZER") }
                            Button(onClick = { sendHardware("SENSORS") }) { Text("ACTUALIZAR") }
                        }
                        if (paused) Button(onClick = { requestPermissionsIfNeeded() }) { Text("ACTIVAR KARBYS") }

                        Spacer(Modifier.height(20.dp))
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Compatibilidad", style = MaterialTheme.typography.titleSmall)
                                Text(deviceCheck, style = MaterialTheme.typography.bodySmall)
                                Text("Modelo Gemini: compatible automático (2.0-flash -> 1.5-flash). No se descarga modelo local.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        requestPermissionsIfNeeded()

        // Auto-check actualización al iniciar (silencioso)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val info = UpdateManager.checkForUpdate(this@MainActivity)
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (info.available) {
                        updateAvailable = true
                        latestVersion = info.latestVersion
                        updateStatus = "Nueva versión ${info.latestVersion} disponible. Toca DESCARGAR."
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun checkDeviceCompatibility() {
        val sb = StringBuilder()
        val ram = try {
            val actManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            memInfo.totalMem / (1024 * 1024)
        } catch (_: Exception) { -1L }

        val hasSpeech = SpeechRecognizer.isRecognitionAvailable(this)
        val hasTts = try {
            val intent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        } catch (_: Exception) { false }

        val cores = Runtime.getRuntime().availableProcessors()
        val androidVer = Build.VERSION.SDK_INT

        sb.append("Android $androidVer, $cores núcleos")
        if (ram > 0) sb.append(", ${ram}MB RAM")
        sb.append(". ")

        if (!hasSpeech) sb.append("Instala Google app para voz. ")
        if (!hasTts) sb.append("Instala Google TTS. ")

        // Todo gama media-alta debería pasar
        if (androidVer >= 26 && cores >= 4) {
            sb.append("✓ Compatible con WayCore.")
        } else if (androidVer >= 26) {
            sb.append("✓ Compatible (mínimo).")
        } else {
            sb.append("✗ Android muy viejo, necesita 8.0+")
        }

        // Chequeo de Gemini: ya no hay modelo local, por eso siempre compatible
        sb.append(" Gemini en nube, sin descarga local.")

        deviceCheck = sb.toString()
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
