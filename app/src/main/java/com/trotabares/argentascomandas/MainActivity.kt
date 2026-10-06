package com.trotabares.argentascomandas

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.p2p.*
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.io.BufferedReader
import java.io.InputStreamReader
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
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.WifiP2pDnsSdServiceRequest
import android.net.wifi.p2p.WifiP2pManager
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.lang.reflect.Method

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
    private val wifiPort = 8988
    private var p2p: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private val p2pDevices = linkedMapOf<String,String>()
    @Volatile private var p2pSocket: Socket? = null
    @Volatile private var p2pServer: ServerSocket? = null
    @Volatile private var p2pConnected = false
    private val nearbyStrategy = Strategy.P2P_POINT_TO_POINT
    private val nearbyClient by lazy { Nearby.getConnectionsClient(this) }
    @Volatile private var nearbyEndpointId: String? = null
    private val nearbyEndpoints = linkedMapOf<String, String>()
    @Volatile private var nearbyDiscoveryRunning = false
    @Volatile private var nearbyAdvertising = false
    @Volatile private var pendingIncomingSocket: BluetoothSocket? = null
    @Volatile private var pendingIncomingToken = 0L
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    private val nearbyPayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes()
                if (bytes != null) receiveNearbyPayload(bytes)
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val nearbyConnectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            nearbyClient.acceptConnection(endpointId, nearbyPayloadCallback)
                .addOnFailureListener { e ->
                    state("DESCONECTADO", "Nearby: no se pudo aceptar la conexión (${e.message ?: "error"})")
                }
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val code = result.status.statusCode
            if (code == ConnectionsStatusCodes.STATUS_OK) {
                nearbyEndpointId = endpointId
                nearbyDiscoveryRunning = false
                try { nearbyClient.stopDiscovery() } catch (_: Exception) {}
                state("CONECTADO", "Conectado con Argentas por Nearby")
                js("window.dispatchEvent(new CustomEvent('argentas-bluetooth',{detail:{type:'authorized'}}));")
            } else {
                nearbyEndpointId = null
                state("DESCONECTADO", "Nearby: conexión falló ($code)")
            }
        }
        override fun onDisconnected(endpointId: String) {
            if (nearbyEndpointId == endpointId) nearbyEndpointId = null
            state("DESCONECTADO", "Conexión con Argentas finalizada")
        }
    }

    private val nearbyDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            nearbyEndpoints[endpointId] = info.endpointName.ifBlank { "Argentas" }
            publishNearbyDevices()
        }
        override fun onEndpointLost(endpointId: String) {
            nearbyEndpoints.remove(endpointId)
            publishNearbyDevices()
        }
    }

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
        setupP2P()
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
            val permissions = mutableListOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= 32) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            val needed = permissions.filter {
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
        startP2PService()
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
            val connected = synchronized(bleLock) {
                bleGatt != null && bleCharacteristic != null
            } || gattPeer != null
            if (!connected) {
                state("LISTO", if (discovered.isEmpty()) "No hay otros Argentas abiertos en este momento." else "Búsqueda finalizada")
            }
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

    @SuppressLint("MissingPermission")
    private fun setupP2P() {
        p2p = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager ?: return
        p2pChannel = p2p!!.initialize(this, mainLooper, object : WifiP2pManager.ChannelListener {
            override fun onChannelDisconnected() { state("ERROR","Wi-Fi Direct perdió el canal") }
        })
        p2pReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = i.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        if (info?.groupFormed == true) handleP2PConnection(info)
                        else if (p2pConnected) { closeP2P(); state("DESCONECTADO","Wi-Fi Direct finalizado") }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION ->
                        if (i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,-1) != WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                            state("ERROR","Activá Wi-Fi")
                }
            }
        }
        registerReceiver(p2pReceiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        })
        startP2PService()
    }

    @SuppressLint("MissingPermission")
    private fun startP2PService() {
        val m=p2p ?: return
        val ch=p2pChannel ?: return
        val service=WifiP2pDnsSdServiceInfo.newInstance("Argentas-Comandas","_argentas._tcp",mapOf("app" to "Argentas-Comandas"))
        m.clearLocalServices(ch,null)
        m.addLocalService(ch,service,object:WifiP2pManager.ActionListener{
            override fun onSuccess(){state("LISTO","Argentas visible por Wi-Fi Direct")}
            override fun onFailure(r:Int){state("ERROR","No se pudo publicar Argentas por Wi-Fi Direct ($r)")}
        })
    }

    @SuppressLint("MissingPermission")
    private fun startP2PDiscovery() {
        val m=p2p ?: return
        val ch=p2pChannel ?: return
        p2pDevices.clear()
        publishP2PDevices()
        state("BUSCANDO","Buscando únicamente Argentas abiertos…")
        m.setDnsSdResponseListeners(ch,
            {name,type,device->
                if(name=="Argentas-Comandas" && type=="_argentas._tcp"){
                    p2pDevices[device.deviceAddress]=device.deviceName.ifBlank{"Argentas"}
                    publishP2PDevices()
                }
            },
            {_,_,_,_->}
        )
        val req=WifiP2pDnsSdServiceRequest.newInstance("_argentas._tcp")
        m.clearServiceRequests(ch,null)
        m.addServiceRequest(ch,req,object:WifiP2pManager.ActionListener{
            override fun onSuccess(){
                m.discoverServices(ch,object:WifiP2pManager.ActionListener{
                    override fun onSuccess(){}
                    override fun onFailure(r:Int){state("ERROR","Wi-Fi Direct no pudo buscar Argentas ($r)")}
                })
            }
            override fun onFailure(r:Int){state("ERROR","Wi-Fi Direct no pudo preparar la búsqueda ($r)")}
        })
        executor.execute {
            try{Thread.sleep(10000)}catch(_:Exception){}
            try{m.clearServiceRequests(ch,null)}catch(_:Exception){}
            state("LISTO",if(p2pDevices.isEmpty())"No hay otros Argentas abiertos en este momento." else "Búsqueda finalizada")
        }
    }

    private fun publishP2PDevices(){
        val a=JSONArray()
        p2pDevices.toSortedMap().forEach{(id,n)->a.put(JSONObject().put("name",n).put("address",id))}
        js("window.dispatchEvent(new CustomEvent(" + JSONObject.quote("argentas-bluetooth") + ",{detail:{type:" + JSONObject.quote("devices") + ",payload:{devices:" + a.toString() + "}}}));")
    }

    @SuppressLint("MissingPermission")
    private fun connectP2P(address:String){
        val m=p2p ?: return
        val ch=p2pChannel ?: return
        m.clearServiceRequests(ch,null)
        state("CONECTANDO","Conectando directamente por Wi-Fi Direct…")
        m.connect(ch,WifiP2pConfig().apply{deviceAddress=address},object:WifiP2pManager.ActionListener{
            override fun onSuccess(){}
            override fun onFailure(r:Int){state("DESCONECTADO","Wi-Fi Direct no pudo conectar ($r)")}
        })
    }

    private fun handleP2PConnection(info:WifiP2pInfo){
        if(info.isGroupOwner) startP2PServer()
        else info.groupOwnerAddress?.let{connectP2PSocket(it)}
    }

    private fun startP2PServer(){
        executor.execute{
            try{
                p2pServer=ServerSocket(wifiPort)
                val s=p2pServer!!.accept()
                p2pServer?.close()
                p2pServer=null
                attachP2PSocket(s)
            }catch(_:Exception){state("DESCONECTADO","Wi-Fi Direct: canal servidor falló")}
        }
    }

    private fun connectP2PSocket(ip:InetAddress){
        executor.execute{
            try{
                val s=Socket()
                s.connect(java.net.InetSocketAddress(ip,wifiPort),8000)
                attachP2PSocket(s)
            }catch(_:Exception){state("DESCONECTADO","Wi-Fi Direct: canal cliente falló")}
        }
    }

    private fun attachP2PSocket(s:Socket){
        p2pSocket=s
        p2pConnected=true
        state("CONECTADO","Conectado directamente por Wi-Fi Direct")
        js("window.dispatchEvent(new CustomEvent(" + JSONObject.quote("argentas-bluetooth") + ",{detail:{type:" + JSONObject.quote("authorized") + "}}));")
        executor.execute{
            try{
                val r=BufferedReader(InputStreamReader(s.getInputStream(),Charsets.UTF_8))
                while(true){
                    val line=r.readLine() ?: break
                    if(line.isNotBlank()) js("window.onBluetoothMessage&&window.onBluetoothMessage("+JSONObject.quote(line)+");")
                }
            }catch(_:Exception){}
            finally{
                closeP2P()
                state("DESCONECTADO","Conexión Wi-Fi Direct finalizada")
            }
        }
    }

    private fun sendP2P(message:String){
        val s=p2pSocket
        if(!p2pConnected||s==null){state("DESCONECTADO","No hay conexión Wi-Fi Direct activa");return}
        writerExecutor.execute{
            try{
                val out=s.getOutputStream()
                synchronized(out){
                    out.write((message.replace("\r","").replace("\n","")+"\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }catch(_:Exception){
                closeP2P()
                state("DESCONECTADO","Wi-Fi Direct perdió el canal")
            }
        }
    }

    private fun closeP2P(){
        p2pConnected=false
        try{p2pSocket?.close()}catch(_:Exception){}
        p2pSocket=null
        try{p2pServer?.close()}catch(_:Exception){}
        p2pServer=null
    }

    private fun devices(){
        if(Build.VERSION.SDK_INT>=33&&ActivityCompat.checkSelfPermission(this,Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED){
            ensurePermissions()
            return
        }
        startP2PDiscovery()
    }

    private val bleLock = Any()
    private var bleGatt: android.bluetooth.BluetoothGatt? = null
    private var bleCharacteristic: android.bluetooth.BluetoothGattCharacteristic? = null
    private var serverCharacteristic: android.bluetooth.BluetoothGattCharacteristic? = null
    private var gattServer: android.bluetooth.BluetoothGattServer? = null
    @Volatile private var gattServiceReady = false
    @Volatile private var gattPeer: BluetoothDevice? = null
    @Volatile private var connectedAddress: String? = null
    @Volatile private var reconnectAttempts = 0
    @Volatile private var reconnectScheduled = false
    @Volatile private var serviceDiscoveryAttempt = 0
    private val gattDiscoveryExecutor = Executors.newSingleThreadScheduledExecutor()
    private val bleIncoming = StringBuilder()
    @Volatile private var bleReceivedAny = false
    @Volatile private var pendingWriteLatch: CountDownLatch? = null
    @Volatile private var pendingWriteStatus = -1
    @Volatile private var pendingWriteGatt: android.bluetooth.BluetoothGatt? = null
    private val CHARACTERISTIC_UUID = UUID.fromString("7f8d7b9a-4a3d-4c0e-9b0d-2b0d6c7e9a12")
    private val DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bleGattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
            if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                try { gatt.close() } catch (_: Exception) {}
                synchronized(bleLock) {
                    if (bleGatt === gatt) { bleGatt = null; bleCharacteristic = null }
                }
                val address = try { gatt.device.address } catch (_: Exception) { null }
                state("DESCONECTADO", if (status == 8) "BLE: tiempo de conexión agotado (8)" else "BLE: error de conexión ($status)")
                if (address != null && address == connectedAddress) scheduleBleReconnect(address)
                return
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                state("CONECTANDO", "BLE conectado; buscando canal Argentas…")
                serviceDiscoveryAttempt = 0
                val started = gatt.discoverServices()
                if (!started) state("DESCONECTADO", "BLE: Android no pudo iniciar el descubrimiento del canal")
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                try { gatt.close() } catch (_: Exception) {}
                synchronized(bleLock) {
                    if (bleGatt === gatt) { bleGatt = null; bleCharacteristic = null }
                }
                val address = try { gatt.device.address } catch (_: Exception) { null }
                state("DESCONECTADO", "Conexión BLE finalizada")
                if (address != null && address == connectedAddress) scheduleBleReconnect(address)
            }
        }

        override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
            if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                state("DESCONECTADO", "BLE: descubrimiento de servicios falló ($status)")
                return
            }
            val service = gatt.getService(uuid)
            if (service == null) {
                val uuids = gatt.services.joinToString(",") { it.uuid.toString() }
                if (serviceDiscoveryAttempt < 2) {
                    serviceDiscoveryAttempt += 1
                    state("CONECTANDO", "BLE conectado; actualizando servicios Argentas…")
                    try {
                        val refresh: Method = gatt.javaClass.getMethod("refresh")
                        refresh.isAccessible = true
                        refresh.invoke(gatt)
                    } catch (_: Exception) {}
                    gattDiscoveryExecutor.schedule({
                        try { gatt.discoverServices() } catch (_: Exception) {}
                    }, 700, TimeUnit.MILLISECONDS)
                    return
                }
                state("DESCONECTADO", "BLE conectado, pero Argentas no aparece entre los servicios ($uuids)")
                return
            }
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
            if (pendingWriteGatt === gatt) {
                pendingWriteStatus = status
                pendingWriteLatch?.countDown()
            }
            if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                state("DESCONECTADO", "BLE: no se pudo enviar un bloque Bluetooth ($status)")
            }
        }

        override fun onCharacteristicChanged(gatt: android.bluetooth.BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic, value: ByteArray) {
            receiveBleChunk(value)
        }

        @Suppress("DEPRECATION")
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
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    gattPeer = device
                    state("CONECTANDO", "El otro Argentas entró al canal BLE…")
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED && gattPeer?.address == device.address) {
                    gattPeer = null
                    state("DESCONECTADO", "Conexión BLE finalizada")
                }
            }
            override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: android.bluetooth.BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                if (descriptor.uuid == DESCRIPTOR_UUID && value.contentEquals(android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    descriptor.value = value.clone()
                    gattPeer = device
                    state("CONECTADO", "Conectado con Argentas por BLE")
                    js("window.dispatchEvent(new CustomEvent('argentas-bluetooth',{detail:{type:'authorized'}}));")
                }
                if (responseNeeded) gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
            }
            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: android.bluetooth.BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                if (characteristic.uuid != CHARACTERISTIC_UUID) {
                    if (responseNeeded) gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                    return
                }
                bleReceivedAny = true
                state("CONECTADO", "Canal BLE recibiendo datos")
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
            android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ or android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE or android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ or android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        characteristic.addDescriptor(android.bluetooth.BluetoothGattDescriptor(DESCRIPTOR_UUID, android.bluetooth.BluetoothGattDescriptor.PERMISSION_READ or android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(characteristic)
        serverCharacteristic = characteristic
        gattServiceReady = false
        gattServer = opened
        if (!opened.addService(service)) { opened.close(); gattServer = null; state("ERROR", "No se pudo publicar el canal BLE de Argentas"); return }
        state("ESPERANDO", "Preparando canal BLE de Argentas…")
    }

    @SuppressLint("MissingPermission")
    private fun scheduleBleReconnect(address: String) {
        if (reconnectScheduled || reconnectAttempts >= 5) return
        reconnectScheduled = true
        reconnectAttempts += 1
        val delay = (reconnectAttempts * 700L).coerceAtMost(3500L)
        state("CONECTANDO", "BLE se cortó; reconectando automáticamente…")
        executor.execute {
            try { Thread.sleep(delay) } catch (_: InterruptedException) { reconnectScheduled = false; return@execute }
            reconnectScheduled = false
            if (connectedAddress == address && synchronized(bleLock) { bleGatt == null }) {
                connect(address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String) = connectP2P(address)



    @SuppressLint("MissingPermission")
    private fun sendBleFromServer(message: String) {
        val server = gattServer ?: return
        val device = gattPeer ?: return
        val characteristic = serverCharacteristic ?: return
        val payload = (message.replace("\r", "").replace("\n", "") + "\n").toByteArray(Charsets.UTF_8)
        writerExecutor.execute {
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + 20, payload.size)
                val chunk = payload.copyOfRange(offset, end)
                var sent = false
                repeat(5) {
                    val result = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(device, characteristic, false, chunk)
                        } else {
                            characteristic.value = chunk
                            @Suppress("DEPRECATION")
                            if (server.notifyCharacteristicChanged(device, characteristic, false)) 0 else -1
                        }
                    } catch (_: Exception) {
                        -1
                    }
                    if (result == 0) {
                        sent = true
                        return@repeat
                    }
                    try { Thread.sleep(120) } catch (_: InterruptedException) { return@execute }
                }
                if (!sent) {
                    state("DESCONECTADO", "BLE: Android rechazó el envío de un bloque al otro Argentas")
                    return@execute
                }
                offset = end
                try { Thread.sleep(35) } catch (_: InterruptedException) { return@execute }
            }
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
    private fun sendNearby(message: String) {
        val endpoint = nearbyEndpointId
        if (endpoint == null) {
            state("DESCONECTADO", "No hay conexión Nearby activa")
            return
        }
        val bytes = message.toByteArray(Charsets.UTF_8)
        if (bytes.size > 1024 * 1024) {
            state("ERROR", "Mensaje de sincronización demasiado grande")
            return
        }
        nearbyClient.sendPayload(endpoint, Payload.fromBytes(bytes))
            .addOnFailureListener { e ->
                state("DESCONECTADO", "Nearby no pudo enviar la sincronización (${e.message ?: "error"})")
            }
    }

    private fun receiveNearbyPayload(bytes: ByteArray) {
        try {
            val message = String(bytes, Charsets.UTF_8)
            js("window.onBluetoothMessage&&window.onBluetoothMessage(" + JSONObject.quote(message) + ");")
            state("CONECTADO", "Datos recibidos de Argentas")
        } catch (_: Exception) {
            state("ERROR", "Nearby recibió datos inválidos")
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
        if (message.length > 1024 * 1024) { state("ERROR", "Mensaje de sincronización demasiado grande"); return }
        sendP2P(message)
    }

    private fun closeConnection() {
        synchronized(bleLock) {
            try { bleGatt?.close() } catch (_: Exception) {}
            bleGatt = null; bleCharacteristic = null; bleIncoming.setLength(0); bleReceivedAny = false
            try { gattServer?.close() } catch (_: Exception) {}
            gattServer = null
            gattServiceReady = false
            gattPeer = null
            connectedAddress = null
            nearbyEndpointId = null
            reconnectAttempts = 5
            reconnectScheduled = false
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
        closeP2P()
        try { p2pChannel?.let { p2p?.clearLocalServices(it,null); p2p?.clearServiceRequests(it,null) } } catch (_: Exception) {}
        try { p2pReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        stopPresenceScan()
        try { bleAdvertiser?.stopAdvertising(bleAdvertiseCallback) } catch (_: Exception) {}
        executor.shutdownNow()
        writerExecutor.shutdownNow()
        timeoutExecutor.shutdownNow()
        webView.removeJavascriptInterface("ArgentasNativeBluetooth")
        super.onDestroy()
    }
}
