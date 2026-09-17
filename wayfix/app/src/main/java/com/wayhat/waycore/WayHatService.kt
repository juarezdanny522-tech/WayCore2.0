package com.wayhat.waycore

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * WayCore <-> WayHat Bluetooth Classic SPP link.
 * Telemetry is kept as the authoritative hardware snapshot for Karbys.
 * All hardware commands are allow-listed and acknowledged by WayHat.
 */
class WayHatService : Service() {
    companion object {
        const val ACTION_START = "com.wayhat.waycore.WAYHAT_START"
        const val ACTION_STOP = "com.wayhat.waycore.WAYHAT_STOP"
        const val ACTION_COMMAND = "com.wayhat.waycore.WAYHAT_COMMAND"
        const val ACTION_STATUS = "com.wayhat.waycore.WAYHAT_STATUS"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_JSON = "json"
        const val DEVICE_NAME = "WayHat-Karbys"
        private const val CHANNEL = "wayhat_link"
        private const val NOTIFICATION_ID = 2601
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @Volatile private var instance: WayHatService? = null
        @Volatile private var latestTelemetry: String = "{\"type\":\"telemetry\",\"available\":false}"

        fun telemetrySnapshot(): String = latestTelemetry

        suspend fun executeTool(name: String, args: JSONObject): String =
            instance?.executeAllowedTool(name, args)
                ?: "WayHat no está disponible en este momento."
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = true
    @Volatile private var connected = false
    @Volatile private var connecting = false
    private var socket: BluetoothSocket? = null
    private var writer: PrintWriter? = null
    private var lastStatus = "Iniciando enlace Bluetooth…"
    private val commandCounter = AtomicInteger(0)
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private lateinit var vibrator: Vibrator
    private var lastVibrationAt = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        startForegroundCompat()
        scope.launch { connectionLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_COMMAND -> intent.getStringExtra(EXTRA_JSON)?.let { sendJson(it) }
            ACTION_STOP -> {
                running = false
                closeSocket()
                stopSelf()
            }
            ACTION_START -> scope.launch { if (!connected) tryConnect() }
        }
        return START_STICKY
    }

    private suspend fun connectionLoop() {
        while (running) {
            if (!connected && !connecting) tryConnect()
            delay(if (connected) 1000L else 2500L)
        }
    }

    private suspend fun tryConnect() {
        if (connecting) return
        connecting = true
        try {
            if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != 0) {
                status(false, "Permiso Bluetooth pendiente")
                return
            }

            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                status(false, "Este celular no tiene Bluetooth Classic")
                return
            }
            if (!adapter.isEnabled) {
                status(false, "Bluetooth está apagado")
                return
            }

            val device = adapter.bondedDevices.firstOrNull {
                it.name?.equals(DEVICE_NAME, ignoreCase = true) == true
            }
            if (device == null) {
                status(false, "Vincula WayHat-Karbys en Bluetooth")
                return
            }

            status(false, "WayHat vinculado: abriendo conexión SPP…")
            adapter.cancelDiscovery()
            closeSocket()

            val s = connectWithFallbacks(device)
            socket = s
            writer = PrintWriter(OutputStreamWriter(s.outputStream), true)
            connected = true
            status(true, "WayHat conectado por Bluetooth")
            readLoop(s)
        } catch (_: Exception) {
            connected = false
            status(false, "No se pudo conectar; reintentando…")
            closeSocket()
        } finally {
            connecting = false
        }
    }

    private fun connectWithFallbacks(device: BluetoothDevice): BluetoothSocket {
        var last: Exception? = null
        try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect(); return s
        } catch (e: Exception) { last = e; try { socket?.close() } catch (_: Exception) {} }
        try {
            val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            s.connect(); return s
        } catch (e: Exception) { last = e }
        try {
            @Suppress("DEPRECATION")
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val s = method.invoke(device, 1) as BluetoothSocket
            s.connect(); return s
        } catch (e: Exception) { last = e }
        throw last ?: IllegalStateException("SPP connection failed")
    }

    private fun readLoop(s: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.inputStream))
            while (running && s.isConnected) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) handleIncoming(line)
            }
        } catch (_: Exception) {
        } finally {
            connected = false
            closeSocket()
            if (running) status(false, "WayHat desconectado; reconectando…")
        }
    }

    private fun handleIncoming(line: String) {
        try {
            val obj = JSONObject(line)
            when (obj.optString("type")) {
                "telemetry" -> {
                    latestTelemetry = line
                    handleSafetyVibration(obj)
                    sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("telemetry", line))
                }
                "ack" -> {
                    val id = obj.optString("id")
                    val waiter = pendingAcks.remove(id)
                    waiter?.complete(line)
                }
                else -> sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("telemetry", line))
            }
        } catch (_: Exception) {
            // Ignore malformed lines; telemetry remains the last valid snapshot.
        }
    }


    private fun handleSafetyVibration(o: JSONObject) {
        if (!o.optBoolean("available", false)) return
        if (o.optString("mode", "SAFE") != "SAFE") return

        val threshold = o.optInt("threshold", 50).coerceIn(20, 150)
        val tfDistance = o.optInt("tf", -1)
        val right = o.optInt("right", -1)
        val left = o.optInt("left", -1)
        val rear = o.optInt("rear", -1)

        // El TF-Luna mantiene una zona de seguridad mas amplia que los HC-SR04.
        val tfSafetyDistance = threshold.coerceAtLeast(100)
        val dangerDistance = listOf(
            right.takeIf { it > 0 && it <= threshold },
            left.takeIf { it > 0 && it <= threshold },
            rear.takeIf { it > 0 && it <= threshold },
            tfDistance.takeIf { it > 0 && it <= tfSafetyDistance }
        ).filterNotNull().minOrNull() ?: return

        val now = System.currentTimeMillis()
        if (now - lastVibrationAt < 350L) return
        lastVibrationAt = now

        val maxDistance = maxOf(threshold, tfSafetyDistance)
        val gap = ((dangerDistance.toLong() * 320L) / maxDistance.toLong()).coerceIn(35L, 260L)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(90L, 180))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(90L)
            }
        } catch (_: Exception) { }
    }

    private fun sendJson(json: String) {
        scope.launch {
            try {
                val w = writer
                if (connected && w != null) {
                    w.println(json); w.flush()
                } else status(false, "WayHat aún no está conectado")
            } catch (_: Exception) {
                connected = false
                closeSocket()
            }
        }
    }

    /** Only these commands can ever be emitted toward the ESP32. */
    private suspend fun executeAllowedTool(name: String, args: JSONObject): String {
        if (!connected) return "No ejecutado: WayHat no está conectado."

        val commandId = "k${System.currentTimeMillis()}_${commandCounter.incrementAndGet()}"
        val json = when (name) {
            "set_wayhat_sensitivity" -> {
                val cm = args.optInt("centimeters", -1)
                if (cm !in 20..150) return "No ejecutado: la sensibilidad debe estar entre 20 y 150 centímetros."
                JSONObject().put("type", "config").put("id", commandId).put("threshold", cm)
            }
            "set_wayhat_mode" -> {
                val mode = args.optString("mode").uppercase()
                if (mode !in setOf("SAFE", "CHAT")) return "No ejecutado: modo inválido."
                JSONObject().put("type", "config").put("id", commandId).put("mode", mode)
            }
            "set_wayhat_alerts" -> {
                if (!args.has("enabled")) return "No ejecutado: falta enabled."
                JSONObject().put("type", "config").put("id", commandId).put("buzzer", args.optBoolean("enabled"))
            }
            "test_wayhat_alert" -> JSONObject().put("type", "command").put("id", commandId).put("name", "BUZZER_TEST")
            "refresh_wayhat_telemetry" -> JSONObject().put("type", "command").put("id", commandId).put("name", "SENSORS")
            else -> return "No ejecutado: esa función de WayHat no está permitida."
        }

        val waiter = CompletableDeferred<String>()
        pendingAcks[commandId] = waiter
        sendJson(json.toString())
        return try {
            withTimeout(1800L) { waiter.await() }
        } catch (_: CancellationException) {
            pendingAcks.remove(commandId)
            "No pude confirmar el comando porque se perdió la conexión con WayHat."
        }
    }

    private fun status(ok: Boolean, message: String) {
        lastStatus = message
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra("connected", ok).putExtra("message", message))
    }

    private fun closeSocket() {
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null; socket = null; connected = false
        pendingAcks.values.forEach { it.cancel() }
        pendingAcks.clear()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "WayHat", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("WayCore")
            .setContentText("Enlace Bluetooth con WayHat")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(NOTIFICATION_ID, n)
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        closeSocket(); scope.cancel(); super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
