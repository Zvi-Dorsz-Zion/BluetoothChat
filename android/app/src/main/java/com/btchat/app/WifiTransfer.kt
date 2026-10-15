package com.btchat.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Fast path for big files: a direct TCP connection over the local network
 * (same router or a phone hotspot - no internet needed). Bluetooth is only
 * used to agree on address/port/token; see PROTOCOL.md.
 *
 * The side that connects writes the 16-byte token first; the hosting side
 * checks it. Data always flows from the file sender to the receiver, then the
 * receiver answers with one byte: 1 = saved OK.
 */
object WifiTransfer {
    const val MIN_SIZE = 1L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 2500
    private const val IO_TIMEOUT_MS = 30_000
    private const val BUFFER = 64 * 1024
    private val random = SecureRandom()

    fun localIpv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }

    fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray =
        runCatching { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
            .getOrDefault(ByteArray(0))

    /** Listening socket on a random port, protected by a random token. */
    class Host {
        private val server = ServerSocket(0)
        val token = ByteArray(16).also { random.nextBytes(it) }
        val port: Int get() = server.localPort

        /** Waits for a connection that presents the right token, or null on timeout. */
        fun accept(timeoutMs: Int): Socket? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                server.soTimeout = left.toInt()
                val s = try { server.accept() } catch (_: Exception) { return null }
                try {
                    s.soTimeout = 5000
                    val got = ByteArray(16)
                    var read = 0
                    val input = s.getInputStream()
                    while (read < got.size) {
                        val n = input.read(got, read, got.size - read)
                        if (n < 0) throw EOFException()
                        read += n
                    }
                    if (got.contentEquals(token)) {
                        s.soTimeout = IO_TIMEOUT_MS
                        s.tcpNoDelay = true
                        return s
                    }
                } catch (_: Exception) {
                }
                runCatching { s.close() }
            }
        }

        fun close() {
            runCatching { server.close() }
        }
    }

    /** Tries every address in parallel (also bound to Wi-Fi explicitly) and returns the first that connects. */
    fun connectAny(context: Context, ips: List<String>, port: Int, token: ByteArray): Socket? {
        if (ips.isEmpty() || port <= 0 || token.size != 16) return null
        val wifi = wifiNetwork(context)
        val attempts = ips.flatMap { ip -> listOf<Pair<String, Network?>>(ip to null) + (wifi?.let { listOf(ip to it) } ?: emptyList()) }
        val winner = AtomicReference<Socket?>(null)
        val done = CountDownLatch(attempts.size)
        val pool = Executors.newCachedThreadPool()
        for ((ip, network) in attempts) {
            pool.execute {
                val s = Socket()
                try {
                    network?.bindSocket(s)
                    s.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                    if (winner.compareAndSet(null, s)) {
                        s.soTimeout = IO_TIMEOUT_MS
                        s.tcpNoDelay = true
                        s.getOutputStream().apply { write(token); flush() }
                    } else {
                        s.close()
                    }
                } catch (_: Exception) {
                    if (winner.get() === s) winner.set(null)
                    runCatching { s.close() }
                } finally {
                    done.countDown()
                }
            }
        }
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS + 1500
        while (winner.get() == null && System.currentTimeMillis() < deadline) {
            if (done.await(50, TimeUnit.MILLISECONDS)) break
        }
        pool.shutdown()
        return winner.get()
    }

    private fun wifiNetwork(context: Context): Network? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm?.allNetworks?.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    } catch (_: Exception) {
        null
    }

    /** Sender side: streams [size] bytes and waits for the receiver's OK byte. */
    fun push(socket: Socket, input: InputStream, size: Long, progress: (Long) -> Unit): Boolean {
        val out = BufferedOutputStream(socket.getOutputStream(), BUFFER)
        val buffer = ByteArray(BUFFER)
        var sent = 0L
        while (sent < size) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
            if (n < 0) throw EOFException("הקובץ קצר מהצפוי")
            out.write(buffer, 0, n)
            sent += n
            progress(sent)
        }
        out.flush()
        return socket.getInputStream().read() == 1
    }

    /** Receiver side: reads [size] bytes into the target and answers with an OK byte. */
    fun pull(socket: Socket, size: Long, target: BluetoothService.IncomingFile) {
        var ok = true
        try {
            val input = BufferedInputStream(socket.getInputStream(), BUFFER)
            val buffer = ByteArray(BUFFER)
            var received = 0L
            while (received < size) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - received).toInt())
                if (n < 0) throw EOFException()
                target.output.write(buffer, 0, n)
                received += n
                target.onProgress(received)
            }
            target.output.close()
            socket.getOutputStream().apply { write(1); flush() }
        } catch (_: Exception) {
            ok = false
            runCatching { target.output.close() }
        }
        target.onFinished(ok)
    }
}
