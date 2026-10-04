package com.trotabares.argentascomandas

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var devicesText: TextView
    private lateinit var logText: TextView
    private lateinit var testButton: Button

    private val executor = Executors.newCachedThreadPool()
    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var selectedDevice: BluetoothDevice? = null
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var serverSocket: BluetoothServerSocket? = null

    private val serviceName = "Argentas-Comandas"
    private val serviceUuid = UUID.fromString("7f8d7b9a-4a3d-4c0e-9b0d-2b0d6c7e9a11")
    private val permissionRequest = 4107

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        devicesText = findViewById(R.id.devicesText)
        logText = findViewById(R.id.logText)
        testButton = findViewById(R.id.testButton)

        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshPairedDevices() }
        findViewById<Button>(R.id.hostButton).setOnClickListener { startServer() }
        findViewById<Button>(R.id.connectButton).setOnClickListener { connectSelected() }
        testButton.setOnClickListener { sendTestMessage() }

        ensureBluetoothPermissions()
    }

    private fun ensureBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), permissionRequest)
                return
            }
        }
        refreshPairedDevices()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun refreshPairedDevices() {
        if (!hasConnectPermission()) return
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setStatus("Bluetooth no disponible", false)
            return
        }
        if (!adapter.isEnabled) {
            setStatus("Bluetooth apagado", false)
            appendLog("Encendé Bluetooth en los ajustes del teléfono.")
            return
        }

        val paired = adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
        if (paired.isEmpty()) {
            devicesText.text = "Dispositivos vinculados:\n— Ninguno —\n\nVinculá los dos teléfonos desde Ajustes > Bluetooth."
            selectedDevice = null
            return
        }

        selectedDevice = paired.first()
        devicesText.text = "Dispositivos vinculados:\n" +
            paired.mapIndexed { i, d ->
                (i + 1).toString() + ". " + (d.name ?: "Sin nombre") + "\n   " + d.address
            }.joinToString("\n\n") +
            "\n\nSeleccionado: " + (selectedDevice?.name ?: selectedDevice?.address)

        appendLog("Encontrados " + paired.size + " dispositivos vinculados.")
    }

    private fun startServer() {
        if (!hasConnectPermission()) {
            ensureBluetoothPermissions()
            return
        }
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            appendLog("Bluetooth está apagado.")
            return
        }

        executor.execute {
            try {
                closeConnection()
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(serviceName, serviceUuid)
                runOnUiThread {
                    setStatus("ESPERANDO CONEXIÓN…", true)
                    appendLog("Este teléfono quedó esperando al otro Argentas-Comandas.")
                }

                val accepted = serverSocket?.accept()
                serverSocket?.close()
                serverSocket = null

                if (accepted != null) establishConnection(accepted, "conexión entrante")
            } catch (e: IOException) {
                runOnUiThread {
                    setStatus("ERROR DE ESPERA", false)
                    appendLog("Error esperando conexión: " + (e.message ?: "desconocido"))
                }
            }
        }
    }

    private fun connectSelected() {
        if (!hasConnectPermission()) {
            ensureBluetoothPermissions()
            return
        }

        val device = selectedDevice
        if (device == null) {
            appendLog("No hay dispositivo seleccionado.")
            return
        }

        executor.execute {
            try {
                closeConnection()
                bluetoothAdapter?.cancelDiscovery()
                runOnUiThread {
                    setStatus("CONECTANDO…", true)
                    appendLog("Conectando con " + (device.name ?: device.address) + "…")
                }

                val newSocket = device.createRfcommSocketToServiceRecord(serviceUuid)
                newSocket.connect()
                establishConnection(newSocket, "conexión saliente")
            } catch (e: IOException) {
                runOnUiThread {
                    setStatus("NO CONECTADO", false)
                    appendLog("Falló la conexión: " + (e.message ?: "desconocido"))
                }
            }
        }
    }

    private fun establishConnection(newSocket: BluetoothSocket, origin: String) {
        socket = newSocket
        output = newSocket.outputStream

        runOnUiThread {
            setStatus("CONECTADO", true)
            testButton.isEnabled = true
            appendLog("Bluetooth RFCOMM conectado (" + origin + ").")
        }

        executor.execute {
            try {
                val input: InputStream = newSocket.inputStream
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) throw IOException("Conexión cerrada por el otro teléfono")
                    if (count > 0) {
                        val message = String(buffer, 0, count, Charsets.UTF_8).trim()
                        runOnUiThread { appendLog("← RECIBIDO: " + message) }
                    }
                }
            } catch (e: IOException) {
                runOnUiThread {
                    testButton.isEnabled = false
                    setStatus("DESCONECTADO", false)
                    appendLog("Conexión finalizada: " + (e.message ?: "sin detalle"))
                }
            }
        }
    }

    private fun sendTestMessage() {
        executor.execute {
            try {
                val message = "HOLA DESDE ARGENTAS-COMANDAS"
                output?.write((message + "\n").toByteArray(Charsets.UTF_8))
                output?.flush()
                runOnUiThread { appendLog("→ ENVIADO: " + message) }
            } catch (e: IOException) {
                runOnUiThread { appendLog("No se pudo enviar: " + (e.message ?: "desconocido")) }
            }
        }
    }

    private fun closeConnection() {
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        output = null
        socket = null
        serverSocket = null
    }

    private fun setStatus(text: String, connectedLike: Boolean) {
        statusText.text = "Bluetooth: " + text
        statusText.setTextColor(getColor(
            if (connectedLike) android.R.color.holo_green_light
            else android.R.color.holo_red_light
        ))
    }

    private fun appendLog(message: String) {
        logText.append(message + "\n")
    }

    override fun onDestroy() {
        closeConnection()
        executor.shutdownNow()
        super.onDestroy()
    }
}
