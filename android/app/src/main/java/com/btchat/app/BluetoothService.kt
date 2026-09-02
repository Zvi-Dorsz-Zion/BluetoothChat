package com.btchat.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Wraps a single Bluetooth RFCOMM connection. Can act as SERVER (listen) or
 * CLIENT (connect to a paired device). Same class is used for both roles.
 */
class BluetoothService(
    private val adapter: BluetoothAdapter,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onTextReceived(text: String)
        fun onFileReceived(fileName: String, bytes: ByteArray)
        fun onError(message: String)
    }

    private val executor = Executors.newCachedThreadPool()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun startServer() {
        stop()
        running = true
        executor.execute {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                    "BtChatServer", BtProtocol.SPP_UUID
                )
                val accepted = serverSocket?.accept() // blocks until a client connects
                serverSocket?.close()
                if (accepted != null) attach(accepted)
            } catch (e: IOException) {
                if (running) listener.onError("שגיאת שרת: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        stop()
        running = true
        executor.execute {
            try {
                val s = device.createRfcommSocketToServiceRecord(BtProtocol.SPP_UUID)
                adapter.cancelDiscovery()
                s.connect() // blocks until connected or throws
                attach(s)
            } catch (e: IOException) {
                if (running) listener.onError("שגיאת חיבור: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attach(connected: BluetoothSocket) {
        socket = connected
        listener.onConnected(connected.remoteDevice?.name ?: "מכשיר לא ידוע")
        readLoop(connected)
    }

    private fun readLoop(connected: BluetoothSocket) {
        val input = connected.inputStream
        try {
            while (running) {
                when (val msg = BtProtocol.readNextMessage(input)) {
                    is BtProtocol.Message.Text -> listener.onTextReceived(msg.text)
                    is BtProtocol.Message.FileReceived ->
                        listener.onFileReceived(msg.fileName, msg.bytes)
                }
            }
        } catch (e: IOException) {
            if (running) listener.onError("החיבור נותק: ${e.message}")
        } finally {
            listener.onDisconnected()
        }
    }

    fun sendText(text: String) {
        val s = socket ?: run { listener.onError("אין חיבור פעיל"); return }
        executor.execute {
            try {
                BtProtocol.sendText(s.outputStream, text)
            } catch (e: IOException) {
                listener.onError("שגיאה בשליחה: ${e.message}")
            }
        }
    }

    fun sendFile(fileName: String, bytes: ByteArray) {
        val s = socket ?: run { listener.onError("אין חיבור פעיל"); return }
        executor.execute {
            try {
                BtProtocol.sendFile(s.outputStream, fileName, bytes)
            } catch (e: IOException) {
                listener.onError("שגיאה בשליחת קובץ: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        serverSocket = null
    }
}
