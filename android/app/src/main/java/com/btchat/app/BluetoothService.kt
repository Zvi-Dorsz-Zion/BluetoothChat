package com.btchat.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the single Bluetooth RFCOMM connection. Listens for incoming connections
 * (server) or connects to a paired device (client). All callbacks arrive on
 * background threads - the listener is responsible for moving to the UI thread.
 *
 * Handshake: after the socket opens, the server side must call [approve] or
 * [reject]. The client waits for that answer (TYPE_ACCEPT / TYPE_REJECT).
 *
 * Files of [WifiTransfer.MIN_SIZE] or more are first offered over Wi-Fi
 * (TYPE_WIFI_OFFER / PULL / DECLINE); if that can't be set up they go over Bluetooth.
 */
@SuppressLint("MissingPermission")
class BluetoothService(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val listener: Listener,
    private val wifiEnabled: () -> Boolean
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
        /** Called on a background thread. Return null to drop the file. */
        fun onFileIncoming(fileName: String, size: Long, viaWifi: Boolean): IncomingFile?
    }

    interface IncomingFile {
        val output: OutputStream
        fun onProgress(received: Long)
        fun onFinished(success: Boolean)
    }

    private val io = Executors.newCachedThreadPool()
    /** Serializes writes to the Bluetooth socket. */
    private val sender = Executors.newSingleThreadExecutor()
    /** One outgoing file at a time; text keeps flowing on [sender] meanwhile when Wi-Fi is used. */
    private val fileJobs = Executors.newSingleThreadExecutor()
    /** Answers (PULL / DECLINE) to our Wi-Fi offers, by offer id. */
    private val wifiAnswers = ConcurrentHashMap<Long, LinkedBlockingQueue<Any>>()
    private val offerIds = AtomicLong(System.currentTimeMillis())

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
                    BtProtocol.TYPE_WIFI_OFFER -> {
                        val offer = BtProtocol.readJson(input, length)
                        io.execute { runCatching { receiveWifiOffer(offer) } }
                    }
                    BtProtocol.TYPE_WIFI_PULL, BtProtocol.TYPE_WIFI_DECLINE -> {
                        val answer = BtProtocol.readJson(input, length)
                        wifiAnswers[answer.optLong("id")]?.offer(type to answer)
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
            listener.onFileIncoming(String(nameBytes, Charsets.UTF_8), size, false)
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

    /**
     * Sends a file: over Wi-Fi when possible (big files), otherwise over Bluetooth.
     * [onWifi] is called once the Wi-Fi connection is up and data starts flowing.
     */
    fun sendFile(
        fileName: String,
        size: Long,
        open: () -> InputStream,
        progress: (Long) -> Unit,
        onWifi: () -> Unit,
        done: (Boolean) -> Unit
    ) {
        fileJobs.execute {
            if (output == null) {
                done(false)
                return@execute
            }
            if (size >= WifiTransfer.MIN_SIZE && wifiEnabled()) {
                val result = try { sendViaWifi(fileName, size, open, progress, onWifi) } catch (_: Exception) { null }
                if (result != null) {
                    done(result)
                    return@execute
                }
            }
            val ok = try {
                sender.submit<Boolean> { sendViaBluetooth(fileName, size, open, progress) }.get()
            } catch (_: Exception) {
                false
            }
            done(ok)
        }
    }

    private fun sendViaBluetooth(fileName: String, size: Long, open: () -> InputStream, progress: (Long) -> Unit): Boolean {
        val out = output ?: return false
        val input = try { open() } catch (_: Exception) { return false }
        return try {
            input.use { BtProtocol.writeFile(out, fileName, size, it, progress) }
            true
        } catch (_: Exception) {
            // A half-written frame corrupts the stream - drop the connection.
            disconnect()
            false
        }
    }

    /**
     * Returns null if a Wi-Fi link could not be set up (caller falls back to
     * Bluetooth), otherwise whether the transfer succeeded.
     */
    private fun sendViaWifi(
        fileName: String,
        size: Long,
        open: () -> InputStream,
        progress: (Long) -> Unit,
        onWifi: () -> Unit
    ): Boolean? {
        val id = offerIds.incrementAndGet()
        val answers = LinkedBlockingQueue<Any>()
        wifiAnswers[id] = answers
        val host = try { WifiTransfer.Host() } catch (_: Exception) { null }
        var socket: Socket? = null
        try {
            val offer = JSONObject().put("id", id).put("name", fileName).put("size", size)
            if (host != null) {
                offer.put("port", host.port)
                    .put("ips", JSONArray(WifiTransfer.localIpv4()))
                    .put("token", WifiTransfer.toHex(host.token))
                io.execute { host.accept(18_000)?.let { answers.offer(it) } }
            }
            if (!writeJsonBlocking(BtProtocol.TYPE_WIFI_OFFER, offer)) return null

            val deadline = System.currentTimeMillis() + 20_000
            while (socket == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                val item = answers.poll(left, TimeUnit.MILLISECONDS) ?: return null
                if (item is Socket) {
                    socket = item
                } else if (item is Pair<*, *>) {
                    val json = item.second as JSONObject
                    if (item.first == BtProtocol.TYPE_WIFI_DECLINE) return null
                    // PULL: the receiver hosts, we connect to it.
                    val ips = json.optJSONArray("ips")?.let { arr -> List(arr.length()) { arr.optString(it) } } ?: emptyList()
                    socket = WifiTransfer.connectAny(
                        context, ips, json.optInt("port"), WifiTransfer.fromHex(json.optString("token"))
                    )
                    if (socket == null) {
                        writeJsonBlocking(BtProtocol.TYPE_WIFI_DECLINE, JSONObject().put("id", id))
                        return null
                    }
                }
            }
            val s = socket ?: return null
            onWifi()
            return try {
                open().use { WifiTransfer.push(s, it, size, progress) }
            } catch (_: Exception) {
                false
            }
        } finally {
            wifiAnswers.remove(id)
            host?.close()
            runCatching { socket?.close() }
            answers.forEach { if (it is Socket && it !== socket) runCatching { it.close() } }
        }
    }

    /** Receiver side of a Wi-Fi offer: connect to the sender, or host and ask it to connect to us. */
    private fun receiveWifiOffer(offer: JSONObject) {
        val id = offer.optLong("id")
        val name = offer.optString("name", "file")
        val size = offer.optLong("size")
        val decline = { writeJsonBlocking(BtProtocol.TYPE_WIFI_DECLINE, JSONObject().put("id", id)) }
        if (!wifiEnabled() || size <= 0) {
            decline()
            return
        }
        val ips = offer.optJSONArray("ips")?.let { arr -> List(arr.length()) { arr.optString(it) } } ?: emptyList()
        var socket = WifiTransfer.connectAny(
            context, ips, offer.optInt("port"), WifiTransfer.fromHex(offer.optString("token"))
        )
        if (socket == null) {
            val host = try { WifiTransfer.Host() } catch (_: Exception) { null }
            if (host != null) {
                try {
                    val pull = JSONObject().put("id", id).put("port", host.port)
                        .put("ips", JSONArray(WifiTransfer.localIpv4()))
                        .put("token", WifiTransfer.toHex(host.token))
                    if (writeJsonBlocking(BtProtocol.TYPE_WIFI_PULL, pull)) socket = host.accept(8_000)
                } finally {
                    host.close()
                }
            }
        }
        if (socket == null) {
            decline()
            return
        }
        socket.use { s ->
            val target = try { listener.onFileIncoming(name, size, true) } catch (_: Exception) { null }
            if (target != null) WifiTransfer.pull(s, size, target)
        }
    }

    private fun writeJsonBlocking(type: Int, json: JSONObject): Boolean = try {
        sender.submit<Boolean> {
            val out = output ?: return@submit false
            BtProtocol.writeJson(out, type, json)
            true
        }.get(10, TimeUnit.SECONDS)
    } catch (_: Exception) {
        false
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
