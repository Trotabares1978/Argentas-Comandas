package com.trotabares.argentascomandas

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val executor = Executors.newCachedThreadPool()
    private val writerExecutor = Executors.newSingleThreadExecutor()
    private val timeoutExecutor = Executors.newScheduledThreadPool(1)
    private val connectionLock = Any()
    @Volatile private var connectionToken = 0L
    @Volatile private var output: OutputStream? = null
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var socket: BluetoothSocket? = null
    private var server: BluetoothServerSocket? = null
    private val uuid = UUID.fromString("7f8d7b9a-4a3d-4c0e-9b0d-2b0d6c7e9a11")
    private val permissionRequest = 4107
    private val discovered = linkedMapOf<String, String>()
    private val argentasCandidates = linkedMapOf<String, BluetoothDevice>()
    private val bleServiceUuid = ParcelUuid(uuid)
    @Volatile private var pendingIncomingSocket: BluetoothSocket? = null
    @Volatile private var pendingIncomingToken = 0L
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(NativeBluetoothBridge(), "ArgentasNativeBluetooth")
        setContentView(webView)
        ensurePermissions()
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun canConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun canScan(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun canAdvertise(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

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

    private fun prepareArgentasBluetooth() {
        if (!canConnect() || !canScan() || !canAdvertise()) return
        val a = adapter ?: return
        if (!a.isEnabled) {
            state("APAGADO", "Activá Bluetooth")
            return
        }
        startServer()
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
            .setConnectable(true)
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
        try {
            scanner.startScan(listOf(filter), settings, bleScanCallback)
        } catch (e: Exception) {
            state("ERROR", e.message ?: "No se pudo iniciar la búsqueda")
            return
        }
        executor.execute {
            try { Thread.sleep(8000) } catch (_: InterruptedException) { return@execute }
            try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
            state("LISTO", if (discovered.isEmpty()) "No hay otros Argentas abiertos en este momento." else "Búsqueda finalizada")
        }
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

    private val bleLock = Any()
    private var bleGatt: android.bluetooth.BluetoothGatt? = null
    private var bleCharacteristic: android.bluetooth.BluetoothGattCharacteristic? = null
    private var gattServer: android.bluetooth.BluetoothGattServer? = null
    @Volatile private var gattServiceReady = false
    @Volatile private var gattPeer: BluetoothDevice? = null
    private val bleIncoming = StringBuilder()
    private val CHARACTERISTIC_UUID = UUID.fromString("7f8d7b9a-4a3d-4c0e-9b0d-2b0d6c7e9a12")
    private val DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bleGattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
            if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                try { gatt.close() } catch (_: Exception) {}
                state("DESCONECTADO", if (status == 8) "BLE: tiempo de conexión agotado (8). El otro Argentas fue encontrado, pero Android perdió el enlace; reintentá." else "BLE: error de conexión ($status)")
                return
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                state("CONECTANDO", "BLE conectado; buscando canal Argentas…")
                gatt.discoverServices()
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                try { gatt.close() } catch (_: Exception) {}
                state("DESCONECTADO", "Conexión BLE finalizada")
            }
        }

        override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
            if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                state("DESCONECTADO", "BLE: descubrimiento de servicios falló ($status)")
                return
            }
            val service = gatt.getService(uuid)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic == null) {
                state("DESCONECTADO", "BLE conectado, pero el servicio Argentas todavía no está disponible")
                return
            }
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                state("DESCONECTADO", "BLE: no se pudieron activar las notificaciones")
                return
            }
            val descriptor = characteristic.getDescriptor(DESCRIPTOR_UUID)
            if (descriptor != null) {
                descriptor.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            } else {
                synchronized(bleLock) { bleGatt = gatt; bleCharacteristic = characteristic }
                state("CONECTADO", "Conectado con Argentas por BLE")
                js("window.dispatchEvent(new CustomEvent('argentas-bluetooth',{detail:{type:'authorized'}}));")
            }
        }

        override fun onDescriptorWrite(gatt: android.bluetooth.BluetoothGatt, descriptor: android.bluetooth.BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == DESCRIPTOR_UUID && status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                val characteristic = gatt.getService(uuid)?.getCharacteristic(CHARACTERISTIC_UUID)
                synchronized(bleLock) { bleGatt = gatt; bleCharacteristic = characteristic }
                state("CONECTADO", "Conectado con Argentas por BLE")
                js("window.dispatchEvent(new CustomEvent('argentas-bluetooth',{detail:{type:'authorized'}}));")
            } else if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                state("DESCONECTADO", "BLE: no se pudieron activar las notificaciones")
            }
        }

        override fun onCharacteristicWrite(gatt: android.bluetooth.BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic, status: Int) {
            if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) state("DESCONECTADO", "BLE: no se pudo enviar el mensaje")
        }

        override fun onCharacteristicChanged(gatt: android.bluetooth.BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic) {
            receiveBleChunk(characteristic.value ?: return)
        }
    }

    private fun startServer() {
        if (!canConnect() || !canAdvertise()) { ensurePermissions(); return }
        startGattServer()
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        if (gattServer != null) return
        val manager = getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val opened = manager.openGattServer(this, object : android.bluetooth.BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: android.bluetooth.BluetoothGattService) {
                if (service.uuid == uuid && status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                    gattServiceReady = true
                    state("ESPERANDO", "Argentas listo para conexión BLE")
                    startPresenceAdvertising()
                } else if (service.uuid == uuid) {
                    gattServiceReady = false
                    state("ERROR", "No se pudo publicar el servicio BLE de Argentas ($status)")
                }
            }
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) state("ESPERANDO", "Argentas disponible para conexión BLE…")
            }
            override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: android.bluetooth.BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
            }
            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: android.bluetooth.BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                if (characteristic.uuid != CHARACTERISTIC_UUID) {
                    if (responseNeeded) gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                    return
                }
                receiveBleChunk(value)
                if (responseNeeded) gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
            }
        }) ?: run {
            state("ERROR", "No se pudo iniciar el servidor BLE de Argentas")
            return
        }
        val service = android.bluetooth.BluetoothGattService(uuid, android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = android.bluetooth.BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ or android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE or android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ or android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        characteristic.addDescriptor(android.bluetooth.BluetoothGattDescriptor(DESCRIPTOR_UUID, android.bluetooth.BluetoothGattDescriptor.PERMISSION_READ or android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(characteristic)
        gattServiceReady = false
        gattServer = opened
        if (!opened.addService(service)) { opened.close(); gattServer = null; state("ERROR", "No se pudo publicar el canal BLE de Argentas"); return }
        state("ESPERANDO", "Preparando canal BLE de Argentas…")
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String) {
        if (!canConnect()) { ensurePermissions(); return }
        val device = argentasCandidates[address] ?: try { adapter?.getRemoteDevice(address) } catch (_: Exception) { null }
        if (device == null) { state("DESCONECTADO", "No se encontró el dispositivo Argentas"); return }
        stopPresenceScan()
        synchronized(bleLock) {
            try { bleGatt?.disconnect() } catch (_: Exception) {}
            try { bleGatt?.close() } catch (_: Exception) {}
            bleGatt = null; bleCharacteristic = null; bleIncoming.setLength(0)
        }
        state("CONECTANDO", "Conectando directamente con Argentas…")
        executor.execute {
            try { Thread.sleep(250) } catch (_: InterruptedException) { return@execute }
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this@MainActivity, false, bleGattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(this@MainActivity, false, bleGattCallback)
            }
            synchronized(bleLock) { bleGatt = gatt }
        }
    }

    private fun receiveBleChunk(bytes: ByteArray) {
        synchronized(bleLock) {
            bleIncoming.append(String(bytes, Charsets.UTF_8))
            while (true) {
                val end = bleIncoming.indexOf("\n")
                if (end < 0) break
                val message = bleIncoming.substring(0, end).trimEnd('\r')
                bleIncoming.delete(0, end + 1)
                if (message.isNotEmpty()) js("window.onBluetoothMessage&&window.onBluetoothMessage(" + JSONObject.quote(message) + ");")
            }
            if (bleIncoming.length > 1024 * 1024) bleIncoming.setLength(0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendBle(message: String) {
        val gatt = synchronized(bleLock) { bleGatt }
        val characteristic = synchronized(bleLock) { bleCharacteristic }
        if (gatt == null || characteristic == null) { state("DESCONECTADO", "No hay conexión BLE activa"); return }
        val payload = (message.replace("\r", "").replace("\n", "") + "\n").toByteArray(Charsets.UTF_8)
        executor.execute {
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + 180, payload.size)
                characteristic.value = payload.copyOfRange(offset, end)
                if (!gatt.writeCharacteristic(characteristic)) { state("DESCONECTADO", "BLE: no se pudo iniciar el envío"); return@execute }
                offset = end
                try { Thread.sleep(50) } catch (_: InterruptedException) { return@execute }
            }
        }
    }

    private fun localDeviceName(): String {
        if (!canConnect()) return "Argentas"
        return try { adapter?.name ?: "Argentas" } catch (_: SecurityException) { "Argentas" }
    }

    private fun sendHandshake(s: BluetoothSocket, message: String) {
        writerExecutor.execute {
            try {
                val out = s.outputStream
                out.write((message.replace("\r", "").replace("\n", "") + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (_: Exception) {
                closeSpecificConnection(s, synchronized(connectionLock) { connectionToken })
            }
        }
    }

    private fun closeSpecificConnection(s: BluetoothSocket, token: Long) {
        synchronized(connectionLock) {
            if (connectionToken != token || socket !== s) return
            try { output?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            output = null
            socket = null
            pendingIncomingSocket = null
            connectionToken += 1
        }
    }

    private fun acceptIncoming() {
        val s = pendingIncomingSocket ?: return
        val token = pendingIncomingToken
        if (s !== socket) return
        sendHandshake(s, JSONObject().put("type", "argentas_connect_accept").toString())
        pendingIncomingSocket = null
        state("CONECTADO", "Conectado con Argentas")
        js("window.dispatchEvent(new CustomEvent('argentas-bluetooth',{detail:{type:'authorized'}}));")
    }

    private fun rejectIncoming() {
        val s = pendingIncomingSocket ?: return
        val token = pendingIncomingToken
        if (s !== socket) return
        sendHandshake(s, JSONObject().put("type", "argentas_connect_reject").toString())
        closeSpecificConnection(s, token)
        state("LISTO", "Solicitud rechazada")
    }

    private fun send(message: String) {
        if (message.length > 900 * 1024) { state("ERROR", "Mensaje Bluetooth demasiado grande"); return }
        sendBle(message)
    }

    private fun closeConnection() {
        synchronized(bleLock) {
            try { bleGatt?.close() } catch (_: Exception) {}
            bleGatt = null; bleCharacteristic = null; bleIncoming.setLength(0)
            try { gattServer?.close() } catch (_: Exception) {}
            gattServer = null
            gattServiceReady = false
            gattPeer = null
        }
        synchronized(connectionLock) {
            connectionToken += 1
            output = null; socket = null
            try { server?.close() } catch (_: Exception) {}
            server = null
        }
    }

    inner class NativeBluetoothBridge {
        @JavascriptInterface fun refresh() { devices() }
        @JavascriptInterface fun startServer() { this@MainActivity.startServer() }
        @JavascriptInterface fun connect(address: String) { this@MainActivity.connect(address) }
        @JavascriptInterface fun acceptIncoming() { this@MainActivity.acceptIncoming() }
        @JavascriptInterface fun rejectIncoming() { this@MainActivity.rejectIncoming() }
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
        executor.shutdownNow()
        writerExecutor.shutdownNow()
        timeoutExecutor.shutdownNow()
        webView.removeJavascriptInterface("ArgentasNativeBluetooth")
        super.onDestroy()
    }
}
