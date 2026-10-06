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
    private val wifiPort = 8988
    private val permissionRequest = 4107

    private var p2p: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private val p2pDevices = linkedMapOf<String, String>()
    @Volatile private var p2pSocket: Socket? = null
    @Volatile private var p2pServer: ServerSocket? = null
    @Volatile private var p2pConnected = false

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
        webView.loadUrl("file:///android_asset/index.html")
        ensurePermissions()
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.INTERNET
        )
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list.distinct().toTypedArray()
    }

    private fun ensurePermissions() {
        val needed = requiredPermissions().filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), permissionRequest)
        } else {
            setupP2P()
        }
    }

    private fun setupP2P() {
        if (p2pChannel != null) {
            startP2PService()
            return
        }
        p2p = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
        val manager = p2p ?: run {
            state("ERROR", "Este teléfono no permite Wi-Fi Direct")
            return
        }

        p2pChannel = manager.initialize(this, mainLooper, object : WifiP2pManager.ChannelListener {
            override fun onChannelDisconnected() {
                p2pChannel = null
                state("ERROR", "Wi-Fi Direct perdió el canal")
            }
        })

        p2pReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (enabled) startP2PService()
                        else state("ERROR", "Activá Wi-Fi para usar la conexión directa")
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        }
                        if (info?.groupFormed == true) {
                            handleP2PConnection(info)
                        } else if (p2pConnected) {
                            closeP2P()
                            state("DESCONECTADO", "Conexión Wi-Fi Direct finalizada")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(p2pReceiver, filter)
        }
        startP2PService()
    }

    private fun startP2PService() {
        val manager = p2p ?: return
        val channel = p2pChannel ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= 33) return

        val service = WifiP2pDnsSdServiceInfo.newInstance(
            "Argentas-Comandas",
            "_argentas._tcp",
            mapOf("app" to "Argentas-Comandas", "v" to "1")
        )
        manager.clearLocalServices(channel, null)
        manager.addLocalService(channel, service, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                state("LISTO", "Argentas visible por Wi-Fi Direct")
            }
            override fun onFailure(reason: Int) {
                state("ERROR", "No se pudo publicar Argentas por Wi-Fi Direct ($reason)")
            }
        })
    }

    private fun startP2PDiscovery() {
        val manager = p2p ?: return
        val channel = p2pChannel ?: return
        p2pDevices.clear()
        publishP2PDevices()
        state("BUSCANDO", "Buscando únicamente Argentas abiertos…")

        manager.setDnsSdResponseListeners(
            channel,
            { instanceName, registrationType, device ->
                if (registrationType == "_argentas._tcp" && instanceName == "Argentas-Comandas") {
                    p2pDevices[device.deviceAddress] = device.deviceName.ifBlank { "Argentas" }
                    publishP2PDevices()
                }
            },
            { _, _, _, _ -> }
        )

        manager.clearServiceRequests(channel, null)
        val request = WifiP2pDnsSdServiceRequest.newInstance("_argentas._tcp")
        manager.addServiceRequest(channel, request, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) {
                        state("ERROR", "Wi-Fi Direct no pudo buscar Argentas ($reason)")
                    }
                })
            }
            override fun onFailure(reason: Int) {
                state("ERROR", "Wi-Fi Direct no pudo preparar la búsqueda ($reason)")
            }
        })

        executor.execute {
            try { Thread.sleep(10000) } catch (_: InterruptedException) { return@execute }
            runOnUiThread {
                try { manager.clearServiceRequests(channel, null) } catch (_: Exception) {}
                if (!p2pConnected) {
                    state(
                        "LISTO",
                        if (p2pDevices.isEmpty()) "No hay otros Argentas abiertos en este momento."
                        else "Búsqueda finalizada"
                    )
                }
            }
        }
    }

    private fun publishP2PDevices() {
        val array = JSONArray()
        p2pDevices.toSortedMap().forEach { (address, name) ->
            array.put(JSONObject().put("name", name).put("address", address))
        }
        js(
            "window.dispatchEvent(new CustomEvent(" +
                JSONObject.quote("argentas-bluetooth") +
                ",{detail:{type:" + JSONObject.quote("devices") +
                ",payload:{devices:" + array + "}}}));"
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectP2P(address: String) {
        val manager = p2p ?: return
        val channel = p2pChannel ?: return
        if (address.isBlank()) return

        try { manager.cancelConnect(channel, null) } catch (_: Exception) {}
        state("CONECTANDO", "Conectando directamente por Wi-Fi Direct…")

        val config = WifiP2pConfig().apply {
            deviceAddress = address
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                state("DESCONECTADO", "Wi-Fi Direct no pudo conectar ($reason)")
            }
        })
    }

    private fun handleP2PConnection(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        if (info.isGroupOwner) {
            startP2PServer()
        } else {
            info.groupOwnerAddress?.let { connectP2PSocket(it) }
        }
    }

    private fun startP2PServer() {
        if (p2pServer != null || p2pConnected) return
        executor.execute {
            try {
                val server = ServerSocket(wifiPort)
                p2pServer = server
                val socket = server.accept()
                try { server.close() } catch (_: Exception) {}
                p2pServer = null
                attachP2PSocket(socket)
            } catch (_: Exception) {
                if (!isFinishing) state("DESCONECTADO", "Wi-Fi Direct: no se pudo abrir el canal de datos")
            }
        }
    }

    private fun connectP2PSocket(address: InetAddress) {
        if (p2pConnected) return
        executor.execute {
            try {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(address, wifiPort), 10000)
                attachP2PSocket(socket)
            } catch (_: Exception) {
                state("DESCONECTADO", "Wi-Fi Direct: no se pudo abrir el canal de datos")
            }
        }
    }

    private fun attachP2PSocket(socket: Socket) {
        p2pSocket = socket
        p2pConnected = true
        state("CONECTADO", "Conectado directamente por Wi-Fi Direct")
        js("window.dispatchEvent(new CustomEvent('argentas-bluetooth',{detail:{type:'authorized'}}));")

        executor.execute {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        js("window.onBluetoothMessage&&window.onBluetoothMessage(" + JSONObject.quote(line) + ");")
                    }
                }
            } catch (_: Exception) {
            } finally {
                closeP2P()
                if (!isFinishing) state("DESCONECTADO", "Conexión Wi-Fi Direct finalizada")
            }
        }
    }

    private fun sendP2P(message: String) {
        val socket = p2pSocket
        if (!p2pConnected || socket == null) {
            state("DESCONECTADO", "No hay conexión Wi-Fi Direct activa")
            return
        }
        writerExecutor.execute {
            try {
                val out = socket.getOutputStream()
                synchronized(out) {
                    out.write((message.replace("\r", "").replace("\n", "") + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (_: Exception) {
                closeP2P()
                state("DESCONECTADO", "Wi-Fi Direct perdió el canal")
            }
        }
    }

    private fun closeP2P() {
        p2pConnected = false
        try { p2pSocket?.close() } catch (_: Exception) {}
        p2pSocket = null
        try { p2pServer?.close() } catch (_: Exception) {}
        p2pServer = null
    }

    private fun devices() {
        if (p2pChannel == null) {
            ensurePermissions()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            ensurePermissions()
            return
        }
        startP2PDiscovery()
    }

    private fun send(message: String) {
        if (message.toByteArray(Charsets.UTF_8).size > 1024 * 1024) {
            state("ERROR", "Mensaje de sincronización demasiado grande")
            return
        }
        sendP2P(message)
    }

    private fun state(value: String, message: String = "") {
        js(
            "window.onBluetoothState&&window.onBluetoothState(" +
                JSONObject.quote(value) + "," + JSONObject.quote(message) + ");"
        )
    }

    private fun js(script: String) {
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    inner class NativeBluetoothBridge {
        @JavascriptInterface fun refresh() { devices() }
        @JavascriptInterface fun startServer() { startP2PService() }
        @JavascriptInterface fun connect(address: String) { connectP2P(address) }
        @JavascriptInterface fun acceptIncoming() {}
        @JavascriptInterface fun rejectIncoming() {}
        @JavascriptInterface fun send(message: String) { this@MainActivity.send(message) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                setupP2P()
            } else {
                state("ERROR", "Argentas necesita los permisos de Wi-Fi Direct para conectar los dos teléfonos")
            }
        }
    }

    override fun onDestroy() {
        closeP2P()
        try {
            p2pReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        try {
            p2pChannel?.let { p2p?.clearLocalServices(it, null) }
        } catch (_: Exception) {}
        executor.shutdownNow()
        writerExecutor.shutdownNow()
        super.onDestroy()
    }
}
