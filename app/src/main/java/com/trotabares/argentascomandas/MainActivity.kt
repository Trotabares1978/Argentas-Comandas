package com.trotabares.argentascomandas

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    private val executor = Executors.newCachedThreadPool()
    private val writerExecutor = Executors.newSingleThreadExecutor()

    private val wifiPort = 8988
    private val permissionRequest = 4107
    private val prefs by lazy { getSharedPreferences("argentas_p2p", Context.MODE_PRIVATE) }
    private val lastPeerKey = "last_peer_address"

    private var p2p: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null

    private val p2pDevices = linkedMapOf<String, String>()

    @Volatile private var p2pSocket: Socket? = null
    @Volatile private var p2pServer: ServerSocket? = null
    @Volatile private var p2pConnected = false
    @Volatile private var connectingTcp = false

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
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.INTERNET
        )

        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            list += Manifest.permission.ACCESS_COARSE_LOCATION
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }

        return list.distinct().toTypedArray()
    }

    private fun hasP2pPermission(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissions() {
        val needed = requiredPermissions().filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                needed.toTypedArray(),
                permissionRequest
            )
        } else {
            setupP2P()
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupP2P() {
        if (!hasP2pPermission()) return

        val manager = p2p ?: run {
            p2p = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
            p2p
        } ?: run {
            state("ERROR", "Este dispositivo no permite Wi-Fi Direct")
            return
        }

        if (p2pChannel != null) return

        p2pChannel = manager.initialize(
            this,
            mainLooper,
            object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    p2pChannel = null
                    closeP2P()
                    state("ERROR", "Wi-Fi Direct perdió el canal")
                }
            }
        )

        p2pReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED

                        if (enabled) {
                            state("LISTO", "Wi-Fi Direct está disponible")
                        } else {
                            closeP2P()
                            state("ERROR", "Activá Wi-Fi para usar la conexión directa")
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        requestPeers()
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        requestConnectionInfo()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                p2pReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(p2pReceiver, filter)
        }

        state("LISTO", "Listo para conexión directa entre Argentas")
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val manager = p2p ?: return
        val channel = p2pChannel ?: return

        manager.requestPeers(channel) { peers: WifiP2pDeviceList ->
            p2pDevices.clear()

            peers.deviceList
                .filter { it.deviceAddress.isNotBlank() }
                .forEach { device ->
                    val name = device.deviceName.ifBlank { "Dispositivo Wi-Fi Direct" }
                    p2pDevices[device.deviceAddress] = name
                }

            publishP2PDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startP2PDiscovery() {
        val manager = p2p ?: return
        val channel = p2pChannel ?: return

        if (!hasP2pPermission()) {
            ensurePermissions()
            return
        }

        p2pDevices.clear()
        publishP2PDevices()
        state("BUSCANDO", "Buscando dispositivos cercanos por Wi-Fi Direct…")

        manager.discoverPeers(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    state("BUSCANDO", "Dispositivos Wi-Fi Direct encontrados a medida que aparecen")
                    requestPeers()
                }

                override fun onFailure(reason: Int) {
                    state(
                        "ERROR",
                        "No se pudo iniciar la búsqueda Wi-Fi Direct ($reason)"
                    )
                }
            }
        )
    }

    private fun publishP2PDevices() {
        val array = JSONArray()

        p2pDevices.entries.sortedWith(compareBy({ it.key != prefs.getString(lastPeerKey, null) }, { it.value.lowercase() })).forEach { (address, name) ->
            array.put(
                JSONObject()
                    .put("name", name)
                    .put("address", address)
            )
        }

        js(
            "window.dispatchEvent(new CustomEvent(" +
                JSONObject.quote("argentas-bluetooth") +
                ",{detail:{type:" +
                JSONObject.quote("devices") +
                ",payload:{devices:" +
                array +
                "}}}));"
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectP2P(address: String) {
        val manager = p2p ?: return
        val channel = p2pChannel ?: return

        if (address.isBlank()) return

        try {
            manager.cancelConnect(channel, null)
        } catch (_: Exception) {
        }

        prefs.edit().putString(lastPeerKey, address).apply()
        closeP2P()
        state("CONECTANDO", "Conectando directamente con el último dispositivo…")

        val config = WifiP2pConfig().apply {
            deviceAddress = address
            groupOwnerIntent = 7
        }

        manager.connect(
            channel,
            config,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    state("CONECTANDO", "Wi-Fi Direct está formando el enlace…")
                }

                override fun onFailure(reason: Int) {
                    state(
                        "DESCONECTADO",
                        "Wi-Fi Direct no pudo conectar ($reason)"
                    )
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        val manager = p2p ?: return
        val channel = p2pChannel ?: return

        manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
            if (!info.groupFormed) {
                if (p2pConnected) {
                    closeP2P()
                    state("DESCONECTADO", "Conexión Wi-Fi Direct finalizada")
                }
                return@requestConnectionInfo
            }

            state(
                "CONECTANDO",
                if (info.isGroupOwner) {
                    "Enlace creado. Preparando canal de datos…"
                } else {
                    "Enlace creado. Conectando al canal de datos…"
                }
            )

            if (info.isGroupOwner) {
                startP2PServer()
            } else {
                val owner = info.groupOwnerAddress
                if (owner != null) {
                    connectP2PSocketWithRetry(owner)
                } else {
                    state("ERROR", "Wi-Fi Direct no informó la dirección del equipo principal")
                }
            }
        }
    }

    private fun startP2PServer() {
        if (p2pServer != null) return

        executor.execute {
            try {
                val server = ServerSocket(wifiPort)
                server.reuseAddress = true
                p2pServer = server

                state("CONECTANDO", "Esperando al otro Argentas…")

                while (!server.isClosed && !isFinishing) {
                    try {
                        val socket = server.accept()
                        if (p2pConnected) {
                            try {
                                socket.close()
                            } catch (_: Exception) {
                            }
                        } else {
                            attachP2PSocket(socket)
                        }
                    } catch (_: Exception) {
                        if (server.isClosed || isFinishing) break
                    }
                }
            } catch (_: Exception) {
                if (!isFinishing && !p2pConnected) {
                    state(
                        "DESCONECTADO",
                        "No se pudo abrir el canal local de datos"
                    )
                }
            } finally {
                if (p2pServer?.isClosed != false) {
                    p2pServer = null
                }
            }
        }
    }

    private fun connectP2PSocketWithRetry(address: InetAddress) {
        if (p2pConnected || connectingTcp) return

        connectingTcp = true

        executor.execute {
            var connected = false

            try {
                repeat(8) { attempt ->
                    if (p2pConnected) {
                        connected = true
                        return@repeat
                    }

                    try {
                        val socket = Socket()
                        socket.tcpNoDelay = true
                        socket.connect(
                            InetSocketAddress(address, wifiPort),
                            900
                        )

                        attachP2PSocket(socket)
                        connected = true
                        return@repeat
                    } catch (_: Exception) {
                        if (attempt < 7) {
                            TimeUnit.MILLISECONDS.sleep(180)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                connectingTcp = false
            }

            if (!connected && !p2pConnected && !isFinishing) {
                state(
                    "DESCONECTADO",
                    "Wi-Fi Direct creó el enlace, pero no se pudo abrir el canal de Argentas"
                )
            }
        }
    }

    private fun attachP2PSocket(socket: Socket) {
        if (p2pConnected) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            return
        }

        try {
            socket.tcpNoDelay = true
        } catch (_: Exception) {
        }

        p2pSocket = socket
        p2pConnected = true
        // El dispositivo que logró establecer TCP es el peer válido para la próxima conexión.
        // Esto acelera el descubrimiento posterior sin depender de Internet ni de un router.

        state("CONECTADO", "Conectado directamente con otro Argentas")
        js(
            "window.dispatchEvent(new CustomEvent('argentas-bluetooth'," +
                "{detail:{type:'authorized'}}));"
        )

        executor.execute {
            try {
                val reader = BufferedReader(
                    InputStreamReader(
                        socket.getInputStream(),
                        Charsets.UTF_8
                    )
                )

                while (true) {
                    val line = reader.readLine() ?: break

                    if (line.isNotBlank()) {
                        js(
                            "window.onBluetoothMessage&&window.onBluetoothMessage(" +
                                JSONObject.quote(line) +
                                ");"
                        )
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (p2pSocket === socket) {
                    closeP2P()

                    if (!isFinishing) {
                        state(
                            "DESCONECTADO",
                            "La conexión directa se cerró; intentando restablecerla…"
                        )
                        requestConnectionInfo()
                    }
                } else {
                    try {
                        socket.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun sendP2P(message: String) {
        val socket = p2pSocket

        if (!p2pConnected || socket == null) {
            state("DESCONECTADO", "No hay conexión directa activa")
            return
        }

        writerExecutor.execute {
            try {
                val out = socket.getOutputStream()
                synchronized(out) {
                    out.write(
                        (
                            message
                                .replace("\r", "")
                                .replace("\n", "") +
                                "\n"
                        ).toByteArray(Charsets.UTF_8)
                    )
                    out.flush()
                }
            } catch (_: Exception) {
                closeP2P()
                state("DESCONECTADO", "La conexión directa perdió el canal; intentando restablecerla…")
                if (!isFinishing) {
                    requestConnectionInfo()
                }
            }
        }
    }

    private fun closeP2P() {
        p2pConnected = false
        connectingTcp = false

        try {
            p2pSocket?.close()
        } catch (_: Exception) {
        }
        p2pSocket = null

        try {
            p2pServer?.close()
        } catch (_: Exception) {
        }
        p2pServer = null
    }

    private fun devices() {
        if (p2pChannel == null) {
            ensurePermissions()
            return
        }

        if (!hasP2pPermission()) {
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
                JSONObject.quote(value) +
                "," +
                JSONObject.quote(message) +
                ");"
        )
    }

    private fun js(script: String) {
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    inner class NativeBluetoothBridge {
        @JavascriptInterface
        fun refresh() {
            devices()
        }

        @JavascriptInterface
        fun startServer() {
            setupP2P()
            requestConnectionInfo()
        }

        @JavascriptInterface
        fun connect(address: String) {
            connectP2P(address)
        }

        @JavascriptInterface
        fun acceptIncoming() {
            requestConnectionInfo()
        }

        @JavascriptInterface
        fun rejectIncoming() {
            closeP2P()
        }

        @JavascriptInterface
        fun send(message: String) {
            this@MainActivity.send(message)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == permissionRequest) {
            if (
                grantResults.isNotEmpty() &&
                grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }
            ) {
                setupP2P()
            } else {
                state(
                    "ERROR",
                    "Argentas necesita permisos de Wi-Fi Direct para conectar los dos equipos"
                )
            }
        }
    }

    override fun onDestroy() {
        closeP2P()

        try {
            p2pReceiver?.let {
                unregisterReceiver(it)
            }
        } catch (_: Exception) {
        }

        executor.shutdownNow()
        writerExecutor.shutdownNow()

        super.onDestroy()
    }
}
