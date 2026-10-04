package com.trotabares.argentascomandas

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
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
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val executor = Executors.newCachedThreadPool()
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var server: BluetoothServerSocket? = null
    private val uuid = UUID.fromString("7f8d7b9a-4a3d-4c0e-9b0d-2b0d6c7e9a11")
    private val permissionRequest = 4107

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
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), permissionRequest)
        }
    }

    private fun canConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun js(script: String) {
        runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    private fun state(value: String, message: String = "") {
        js("window.onBluetoothState&&window.onBluetoothState(" +
            JSONObject.quote(value) + "," + JSONObject.quote(message) + ");")
    }

    private fun devices() {
        if (!canConnect()) { ensurePermissions(); return }
        val a = adapter ?: run { state("NO_DISPONIBLE"); return }
        if (!a.isEnabled) { state("APAGADO", "Activá Bluetooth"); return }
        val arr = JSONArray()
        a.bondedDevices.toList().sortedBy { it.name ?: it.address }.forEach {
            arr.put(JSONObject().put("name", it.name ?: "Sin nombre").put("address", it.address))
        }
        js("window.dispatchEvent(new CustomEvent('argentas-bluetooth',{detail:{type:'devices',payload:{devices:" +
            arr.toString() + "}}}));")
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
                val device = a.getRemoteDevice(address)
                state("CONECTANDO", device.name ?: address)
                val s = device.createRfcommSocketToServiceRecord(uuid)
                s.connect()
                establish(s, "saliente")
            } catch (e: IOException) {
                state("DESCONECTADO", e.message ?: "Falló la conexión")
            }
        }
    }

    private fun establish(s: BluetoothSocket, origin: String) {
        socket = s
        output = s.outputStream
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
                state("DESCONECTADO", e.message ?: "Conexión finalizada")
            }
        }
    }

    private fun send(message: String) {
        executor.execute {
            try {
                val out = output ?: run {
                    state("DESCONECTADO", "No hay teléfono conectado")
                    return@execute
                }
                out.write((message.replace("\r", "").replace("\n", "") + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: IOException) {
                state("DESCONECTADO", e.message ?: "No se pudo enviar")
            }
        }
    }

    private fun closeConnection() {
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        output = null
        socket = null
        server = null
    }

    inner class NativeBluetoothBridge {
        @JavascriptInterface fun refresh() { devices() }
        @JavascriptInterface fun startServer() { this@MainActivity.startServer() }
        @JavascriptInterface fun connect(address: String) { this@MainActivity.connect(address) }
        @JavascriptInterface fun send(message: String) { this@MainActivity.send(message) }
    }

    override fun onDestroy() {
        closeConnection()
        executor.shutdownNow()
        webView.removeJavascriptInterface("ArgentasNativeBluetooth")
        super.onDestroy()
    }
}
