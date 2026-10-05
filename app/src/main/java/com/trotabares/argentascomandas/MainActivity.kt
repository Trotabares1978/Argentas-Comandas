package com.trotabares.argentascomandas

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import android.bluetooth.BluetoothLeAdvertiser
import android.bluetooth.BluetoothLeScanner
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val executor = Executors.newCachedThreadPool()
    private val writerExecutor = Executors.newSingleThreadExecutor()
    private val connectionLock = Any()
    @Volatile private var connectionToken = 0L
    @Volatile private var output: OutputStream? = null
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var socket: BluetoothSocket? = null
    private var server: BluetoothServerSocket? = null
    private val uuid = UUID.fromString("7f8d7b9a-4a3d-4c0e-9b0d-2b0d6c7e9a11")
    private val permissionRequest = 4107
    private val discoverableRequest = 4108
    private val discovered = linkedMapOf<String, String>()
    private val argentasCandidates = linkedMapOf<String, BluetoothDevice>()
    private val bleServiceUuid = ParcelUuid(uuid)
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    private val bleAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            state("LISTO", "Argentas está visible para otros Argentas")
        }
        override fun onStartFailure(errorCode: Int) {
            state("ERROR", "No se pudo publicar Argentas por Bluetooth ($errorCode)")
        }
    }
    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = try { device.address } catch (_: SecurityException) { return }
            val name = try { device.name } catch (_: SecurityException) { null }
            argentasCandidates[address] = device
            discovered[address] = name ?: "Argentas"
            publishDevices()
        }
        override fun onScanFailed(errorCode: Int) {
            state("ERROR", "No se pudo buscar Argentas por Bluetooth ($errorCode)")
        }
    }
    private var receiverRegistered = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(NativeBluetoothBridge(), "ArgentasNativeBluetooth")
        setContentView(webView)
        ensurePermissions()
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun ensurePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ).filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), permissionRequest)
            else prepareArgentasBluetooth()
        } else {
            prepareArgentasBluetooth()
        }
    }

    private fun canAdvertise(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private fun prepareArgentasBluetooth() {
        if (!canConnect() || !canScan() || !canAdvertise()) return
        val a = adapter ?: return
        if (!a.isEnabled) {
            state("APAGADO", "Activá Bluetooth")
            return
        }
        startServer()
        startPresenceAdvertising()
    }

    private fun startPresenceAdvertising() {
        if (!canConnect() || !canAdvertise()) return
        val a = adapter ?: return
        val advertiser = a.bluetoothLeAdvertiser ?: run {
            state("ERROR", "Este equipo no permite publicar Argentas por Bluetooth")
            return
        }
        bleAdvertiser = advertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(bleServiceUuid)
            .setIncludeDeviceName(false)
            .build()
        try { advertiser.stopAdvertising(bleAdvertiseCallback) } catch (_: Exception) {}
        advertiser.startAdvertising(settings, data, bleAdvertiseCallback)
    }

    private fun startPresenceScan() {
        if (!canScan()) { ensurePermissions(); return }
        val a = adapter ?: return
        val scanner = a.bluetoothLeScanner ?: run {
            state("ERROR", "Este equipo no permite buscar Argentas por Bluetooth")
            return
        }
        bleScanner = scanner
        val filter = ScanFilter.Builder().setServiceUuid(bleServiceUuid).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        discovered.clear()
        argentasCandidates.clear()
        publishDevices()
        try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
        state("BUSCANDO", "Buscando únicamente Argentas abiertos…")
        scanner.startScan(listOf(filter), settings, bleScanCallback)
    }

    private fun stopPresenceScan() {
        try { bleScanner?.stopScan(bleScanCallback) } catch (_: Exception) {}
    }

    private fun js(script: String) {
        runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    private fun state(value: String, message: String = "") {
        js("window.onBluetoothState&&window.onBluetoothState(" +
            JSONObject.quote(value) + "," + JSONObject.quote(message) + ");")
    }

    private fun publishDevices() {
        val arr = JSONArray()
        discovered.toSortedMap().forEach { (address, name) ->
            arr.put(JSONObject().put("name", name).put("address", address))
        }
        js("window.dispatchEvent(new CustomEvent('argentas-bluetooth',{detail:{type:'devices',payload:{devices:" +
            arr.toString() + "}}}));")
    }

    private fun devices() {
        if (!canScan() || !canConnect()) { ensurePermissions(); return }
        val a = adapter ?: run { state("NO_DISPONIBLE"); return }
        if (!a.isEnabled) { state("APAGADO", "Activá Bluetooth"); return }
        startPresenceScan()
    }

    private fun makeDiscoverable() {
        if (!canConnect()) { ensurePermissions(); return }
        val a = adapter ?: return
        if (!a.isEnabled) { state("APAGADO", "Activá Bluetooth"); return }
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            startActivityForResult(intent, discoverableRequest)
            state("VISIBLE", "Argentas quedará visible durante 5 minutos")
        } catch (e: Exception) {
            state("ERROR", e.message ?: "No se pudo hacer visible el dispositivo")
        }
    }

    private fun startServer() {
        if (!canConnect()) { ensurePermissions(); return }
        val a = adapter ?: return
        if (!a.isEnabled) { state("APAGADO", "Activá Bluetooth"); return }
        executor.execute {
            try {
                closeConnection()
                server = a.listenUsingRfcommWithServiceRecord("Argentas-Comandas", uuid)
                state("ESPERANDO", "Esperando al otro teléfono…")
                val accepted = server!!.accept()
                try { server?.close() } catch (_: Exception) {}
                server = null
                establish(accepted, "entrante")
            } catch (e: IOException) {
                state("ERROR", e.message ?: "No se pudo esperar")
            }
        }
    }

    private fun connect(address: String) {
        if (!canConnect()) { ensurePermissions(); return }
        val a = adapter ?: return
        executor.execute {
            try {
                closeConnection()
                a.cancelDiscovery()
                val device = argentasCandidates[address] ?: a.getRemoteDevice(address)
                state("CONECTANDO", device.name ?: address)
                val s = device.createRfcommSocketToServiceRecord(uuid)
                try {
                    s.connect()
                    establish(s, "saliente")
                } catch (e: IOException) {
                    try { s.close() } catch (_: Exception) {}
                    throw e
                }
            } catch (e: IOException) {
                state("DESCONECTADO", e.message ?: "Falló la conexión")
            }
        }
    }

    private fun establish(s: BluetoothSocket, origin: String) {
        val token = synchronized(connectionLock) {
            connectionToken += 1
            socket = s
            output = s.outputStream
            connectionToken
        }
        state("CONECTADO", origin)
        executor.execute {
            try {
                val input = s.inputStream
                val buffer = ByteArray(4096)
                val pending = StringBuilder()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) throw IOException("Conexión cerrada")
                    if (n == 0) continue
                    pending.append(String(buffer, 0, n, Charsets.UTF_8))
                    while (true) {
                        val end = pending.indexOf("\n")
                        if (end < 0) break
                        val message = pending.substring(0, end).trimEnd('\r')
                        pending.delete(0, end + 1)
                        if (message.isNotEmpty()) {
                            js("window.onBluetoothMessage&&window.onBluetoothMessage(" +
                                JSONObject.quote(message) + ");")
                        }
                    }
                    if (pending.length > 1024 * 1024) {
                        pending.setLength(0)
                        state("ERROR", "Mensaje Bluetooth demasiado grande")
                    }
                }
            } catch (e: IOException) {
                val current = synchronized(connectionLock) { connectionToken == token && socket === s }
                if (current) {
                    synchronized(connectionLock) {
                        if (socket === s) {
                            try { output?.close() } catch (_: Exception) {}
                            try { socket?.close() } catch (_: Exception) {}
                            output = null
                            socket = null
                            connectionToken += 1
                        }
                    }
                    state("DESCONECTADO", e.message ?: "Conexión finalizada")
                }
            }
        }
    }

    private fun send(message: String) {
        if (message.length > 900 * 1024) {
            state("ERROR", "Mensaje Bluetooth demasiado grande")
            return
        }
        writerExecutor.execute {
            var sentOutput: OutputStream? = null
            try {
                synchronized(connectionLock) {
                    val out = output ?: run {
                        state("DESCONECTADO", "No hay teléfono conectado")
                        return@synchronized
                    }
                    sentOutput = out
                    out.write((message.replace("\r", "").replace("\n", "") + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (e: IOException) {
                synchronized(connectionLock) {
                    if (output === sentOutput) {
                        try { output?.close() } catch (_: Exception) {}
                        try { socket?.close() } catch (_: Exception) {}
                        output = null
                        socket = null
                        connectionToken += 1
                    }
                }
                state("DESCONECTADO", e.message ?: "No se pudo enviar")
            }
        }
    }

    private fun closeConnection() {
        synchronized(connectionLock) {
            connectionToken += 1
            try { output?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            try { server?.close() } catch (_: Exception) {}
            output = null
            socket = null
            server = null
        }
    }

    inner class NativeBluetoothBridge {
        @JavascriptInterface fun refresh() { devices() }
        @JavascriptInterface fun makeDiscoverable() { }
        @JavascriptInterface fun startServer() { this@MainActivity.startServer() }
        @JavascriptInterface fun connect(address: String) { this@MainActivity.connect(address) }
        @JavascriptInterface fun send(message: String) { this@MainActivity.send(message) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            prepareArgentasBluetooth()
        }
    }

    override fun onDestroy() {
        closeConnection()
        stopPresenceScan()
        try { bleAdvertiser?.stopAdvertising(bleAdvertiseCallback) } catch (_: Exception) {}
        if (receiverRegistered) {
            try { unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        executor.shutdownNow()
        writerExecutor.shutdownNow()
        webView.removeJavascriptInterface("ArgentasNativeBluetooth")
        super.onDestroy()
    }
}
