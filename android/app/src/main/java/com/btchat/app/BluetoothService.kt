package com.btchat.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Owns the single Bluetooth RFCOMM connection. Listens for incoming connections
 * (server) or connects to a paired device (client). All callbacks arrive on
 * background threads - the listener is responsible for moving to the UI thread.
 *
 * Handshake: after the socket opens, the server side must call [approve] or
 * [reject]. The client waits for that answer (TYPE_ACCEPT / TYPE_REJECT).
 */
@SuppressLint("MissingPermission")
class BluetoothService(
    private val adapter: BluetoothAdapter,
    private val listener: Listener
) {
    interface Listener {
        fun onListenFailed(message: String)
        fun onIncomingRequest(device: BluetoothDevice)
        fun onAwaitingApproval(device: BluetoothDevice)
        fun onConnected(device: BluetoothDevice)
        fun onConnectFailed(device: BluetoothDevice, message: String)
        fun onRejected(device: BluetoothDevice)
        fun onDisconnected(device: BluetoothDevice)
        fun onTextReceived(text: String)
        /** Called on the reader thread. Return null to drop the file. */
        fun onFileIncoming(fileName: String, size: Long): IncomingFile?
    }

    interface IncomingFile {
        val output: OutputStream
        fun onProgress(received: Long)
        fun onFinished(success: Boolean)
    }

    private val io = Executors.newCachedThreadPool()
    private val sender = Executors.newSingleThreadExecutor()

    @Volatile private var wantListen = false
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: DataOutputStream? = null

    val hasConnection: Boolean get() = socket != null

    // ---- Server ----

    fun startListening() {
        synchronized(this) {
            if (wantListen || socket != null) return
            wantListen = true
        }
        io.execute {
            val ss = try {
                adapter.listenUsingRfcommWithServiceRecord("BtChat", BtProtocol.SPP_UUID)
            } catch (e: Exception) {
                wantListen = false
                listener.onListenFailed(e.message ?: "")
                return@execute
            }
            serverSocket = ss
            if (!wantListen) {
                closeQuietly(ss)
                return@execute
            }
            val accepted = try { ss.accept() } catch (e: IOException) { null }
            val stoppedOnPurpose = !wantListen
            wantListen = false
            serverSocket = null
            closeQuietly(ss)
            if (accepted == null) {
                if (!stoppedOnPurpose) listener.onListenFailed("ההאזנה הופסקה")
                return@execute
            }
            synchronized(this) {
                if (socket != null) {
                    closeQuietly(accepted)
                    return@execute
                }
                attach(accepted)
            }
            listener.onIncomingRequest(accepted.remoteDevice)
            readLoop(accepted)
        }
    }

    fun stopListening() {
        wantListen = false
        serverSocket?.let { closeQuietly(it) }
        serverSocket = null
    }

    fun approve() = sendControl(BtProtocol.TYPE_ACCEPT)

    fun reject() {
        sender.execute {
            try {
                output?.let { BtProtocol.writeControl(it, BtProtocol.TYPE_REJECT) }
                Thread.sleep(500) // let the answer reach the other side before closing
            } catch (_: Exception) {
            }
            disconnect()
        }
    }

    // ---- Client ----

    fun connect(device: BluetoothDevice) {
        stopListening()
        io.execute {
            val s = try {
                device.createRfcommSocketToServiceRecord(BtProtocol.SPP_UUID)
            } catch (e: IOException) {
                listener.onConnectFailed(device, e.message ?: "")
                return@execute
            }
            synchronized(this) { socket = s } // lets disconnect() abort a pending connect
            try {
                adapter.cancelDiscovery()
                s.connect()
            } catch (e: IOException) {
                synchronized(this) { if (socket === s) socket = null }
                closeQuietly(s)
                listener.onConnectFailed(device, e.message ?: "")
                return@execute
            }
            synchronized(this) {
                if (socket !== s) { // cancelled while connecting
                    closeQuietly(s)
                    listener.onConnectFailed(device, "בוטל")
                    return@execute
                }
                attach(s)
            }
            listener.onAwaitingApproval(device)
            readLoop(s)
        }
    }

    // ---- Shared ----

    private fun attach(s: BluetoothSocket) {
        socket = s
        output = DataOutputStream(BufferedOutputStream(s.outputStream, BtProtocol.CHUNK))
    }

    private fun readLoop(s: BluetoothSocket) {
        val device = s.remoteDevice
        try {
            val input = DataInputStream(BufferedInputStream(s.inputStream, BtProtocol.CHUNK))
            loop@ while (true) {
                val type = input.readUnsignedByte()
                val length = input.readInt()
                when (type) {
                    BtProtocol.TYPE_TEXT -> {
                        val bytes = ByteArray(length)
                        input.readFully(bytes)
                        listener.onTextReceived(String(bytes, Charsets.UTF_8))
                    }
                    BtProtocol.TYPE_FILE -> receiveFile(input, length)
                    BtProtocol.TYPE_ACCEPT -> listener.onConnected(device)
                    BtProtocol.TYPE_REJECT -> {
                        listener.onRejected(device)
                        break@loop
                    }
                    else -> BtProtocol.skipFully(input, length.toLong())
                }
            }
        } catch (_: IOException) {
            // connection closed
        } finally {
            synchronized(this) {
                if (socket === s) {
                    socket = null
                    output = null
                }
            }
            closeQuietly(s)
            listener.onDisconnected(device)
        }
    }

    private fun receiveFile(input: DataInputStream, length: Int) {
        val nameLen = input.readUnsignedShort()
        val nameBytes = ByteArray(nameLen)
        input.readFully(nameBytes)
        val size = length.toLong() - 2 - nameLen
        val target = try {
            listener.onFileIncoming(String(nameBytes, Charsets.UTF_8), size)
        } catch (_: Exception) {
            null
        }
        val buffer = ByteArray(BtProtocol.CHUNK)
        var received = 0L
        var writeOk = target != null
        try {
            while (received < size) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - received).toInt())
                if (n < 0) throw EOFException()
                if (writeOk) {
                    try {
                        target!!.output.write(buffer, 0, n)
                    } catch (_: IOException) {
                        writeOk = false // keep draining so the stream stays in sync
                    }
                }
                received += n
                target?.onProgress(received)
            }
        } catch (e: IOException) {
            target?.let { closeQuietly(it.output); it.onFinished(false) }
            throw e
        }
        if (target != null) {
            try {
                target.output.close()
            } catch (_: IOException) {
                writeOk = false
            }
            target.onFinished(writeOk)
        }
    }

    private fun sendControl(type: Int) {
        sender.execute {
            try {
                output?.let { BtProtocol.writeControl(it, type) }
            } catch (_: IOException) {
            }
        }
    }

    fun sendText(text: String, done: (Boolean) -> Unit) {
        sender.execute {
            val out = output
            val ok = try {
                if (out == null) false else {
                    BtProtocol.writeText(out, text)
                    true
                }
            } catch (_: IOException) {
                false
            }
            done(ok)
        }
    }

    fun sendFile(
        fileName: String,
        size: Long,
        open: () -> InputStream,
        progress: (Long) -> Unit,
        done: (Boolean) -> Unit
    ) {
        sender.execute {
            val out = output
            val input = try { if (out == null) null else open() } catch (_: Exception) { null }
            if (out == null || input == null) {
                done(false)
                return@execute
            }
            val ok = try {
                input.use { BtProtocol.writeFile(out, fileName, size, it, progress) }
                true
            } catch (_: Exception) {
                // A half-written frame corrupts the stream - drop the connection.
                disconnect()
                false
            }
            done(ok)
        }
    }

    fun disconnect() {
        val s: BluetoothSocket?
        synchronized(this) {
            s = socket
            socket = null
            output = null
        }
        s?.let { closeQuietly(it) }
    }

    fun shutdown() {
        stopListening()
        disconnect()
    }

    private fun closeQuietly(c: java.io.Closeable) {
        try { c.close() } catch (_: IOException) { }
    }
}
