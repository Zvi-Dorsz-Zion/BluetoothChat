package com.btchat.app

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * App-wide chat state. Lives in [BtChatApp], so the connection is not tied to an
 * Activity (rotation / hiding the app does not drop it). All public methods and
 * UI callbacks run on the main thread.
 */
@SuppressLint("MissingPermission")
class ChatController(private val app: Application) : BluetoothService.Listener {

    enum class State {
        NEED_PERMISSION, NO_BLUETOOTH, BT_OFF, LISTENING,
        CONNECTING, AWAITING_APPROVAL, INCOMING_REQUEST, CONNECTED
    }

    interface Ui {
        fun onStateChanged()
        fun onConversationChanged()
        fun onMessageAdded(position: Int)
        fun onMessageChanged(position: Int, progressOnly: Boolean)
        fun onIncomingRequest()
        fun onNotice(text: String)
        fun onExit()
    }

    val prefs = Prefs(app)
    val history = HistoryStore(app)
    val fileStore = FileStore(app, prefs)

    private val main = Handler(Looper.getMainLooper())
    private val prep = Executors.newSingleThreadExecutor()
    private val ids = AtomicLong(System.currentTimeMillis())

    val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val bt: BluetoothService? = adapter?.let {
        BluetoothService(app, it, this, wifiEnabled = { prefs.wifiEnabled }, directMode = { prefs.directMode })
    }

    var state = if (adapter == null) State.NO_BLUETOOTH else State.NEED_PERMISSION
        private set

    /** Device we are connected / connecting to, or that is asking to connect. */
    @Volatile var peerAddress: String? = null
        private set

    /** Device whose history is on screen. */
    var conversation: String? = null
        private set

    val messages = ArrayList<ChatMessage>()
    var ui: Ui? = null
    var uiVisible = false

    private var started = false
    private var cancelling = false
    private var pendingConnect: String? = null
    private val requestTimeout = Runnable { respondToIncoming(accept = false, remember = false) }
    private val retryListen = Runnable { ensureListening() }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> main.postDelayed(retryListen, 500)
                BluetoothAdapter.STATE_OFF -> {
                    bt?.shutdown()
                    main.removeCallbacks(requestTimeout)
                    Notifications.cancelRequest(app)
                    peerAddress = null
                    pendingConnect = null
                    setState(State.BT_OFF)
                }
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /** Call once Bluetooth permissions are granted. Safe to call repeatedly. */
    fun start() {
        if (adapter == null) return
        if (!started) {
            started = true
            ContextCompat.registerReceiver(
                app, btStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            if (state == State.NEED_PERMISSION) state = State.LISTENING
            ChatService.start(app)
            if (conversation == null) prefs.lastAddress?.let { openConversation(it) }
        }
        ensureListening()
    }

    /** Always open for incoming connections unless busy with one. */
    fun ensureListening() {
        val a = adapter ?: return
        val b = bt ?: return
        if (!started) return
        if (!a.isEnabled) {
            setState(State.BT_OFF)
            return
        }
        when (state) {
            State.CONNECTING, State.AWAITING_APPROVAL, State.INCOMING_REQUEST, State.CONNECTED -> return
            else -> Unit
        }
        if (b.hasConnection) return
        b.startListening()
        setState(State.LISTENING)
    }

    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        bt?.shutdown()
        if (started) {
            try { app.unregisterReceiver(btStateReceiver) } catch (_: Exception) { }
        }
        started = false
        peerAddress = null
        Notifications.cancelRequest(app)
        ChatService.stop(app)
        state = if (adapter == null) State.NO_BLUETOOTH else State.NEED_PERMISSION
        ui?.onExit()
    }

    // ---------------------------------------------------------------- queries

    fun statusText(): String = when (state) {
        State.NEED_PERMISSION -> "נדרשת הרשאת Bluetooth"
        State.NO_BLUETOOTH -> "אין Bluetooth במכשיר"
        State.BT_OFF -> "Bluetooth כבוי"
        State.LISTENING -> "מוכן לקבל חיבורים"
        State.CONNECTING -> "מתחבר אל ${peerName()}..."
        State.AWAITING_APPROVAL -> "ממתין לאישור ב-${peerName()}..."
        State.INCOMING_REQUEST -> "${peerName()} מבקש להתחבר"
        State.CONNECTED -> "מחובר אל ${peerName()}"
    }

    fun peerName(): String = peerAddress?.let { displayName(it) } ?: ""

    fun displayName(address: String): String =
        prefs.nickname(address) ?: systemName(address) ?: address

    fun systemName(address: String): String? =
        try { adapter?.getRemoteDevice(address)?.name } catch (_: Exception) { null }

    /** True when the screen shows the conversation of the connected device. */
    val canSend: Boolean get() = state == State.CONNECTED && conversation != null && conversation == peerAddress

    // ---------------------------------------------------------------- actions

    fun openConversation(address: String) {
        conversation = address
        prefs.lastAddress = address
        messages.clear()
        messages.addAll(history.load(address))
        Notifications.cancelMessage(app, address)
        ui?.onConversationChanged()
    }

    fun connect(address: String) {
        val a = adapter ?: return
        val b = bt ?: return
        if (!a.isEnabled) {
            notice("יש להפעיל Bluetooth")
            return
        }
        if (state == State.CONNECTED || state == State.CONNECTING ||
            state == State.AWAITING_APPROVAL || state == State.INCOMING_REQUEST
        ) {
            if (peerAddress == address && state != State.INCOMING_REQUEST) {
                openConversation(address)
                return
            }
            pendingConnect = address // connect after the current connection closes
            disconnect()
            return
        }
        val device = try { a.getRemoteDevice(address) } catch (_: IllegalArgumentException) { return }
        cancelling = false
        peerAddress = address
        openConversation(address)
        setState(State.CONNECTING)
        b.connect(device)
    }

    /** Disconnect, cancel a pending connect, or reject an incoming request. */
    fun disconnect() {
        if (state == State.INCOMING_REQUEST) {
            respondToIncoming(accept = false, remember = false)
            return
        }
        cancelling = true
        bt?.disconnect()
    }

    fun respondToIncoming(accept: Boolean, remember: Boolean) {
        if (state != State.INCOMING_REQUEST) return
        main.removeCallbacks(requestTimeout)
        Notifications.cancelRequest(app)
        val address = peerAddress ?: return
        if (accept) {
            if (remember) prefs.setTrusted(address, true)
            bt?.approve()
            markConnected(address)
        } else {
            cancelling = true
            bt?.reject()
        }
    }

    fun rename(address: String, name: String?) {
        prefs.setNickname(address, name)
        ui?.onStateChanged()
        ChatService.update(app, statusText())
    }

    /** Devices that ever connected through BT Chat (or have a conversation), newest activity first. */
    fun knownDevices(): List<String> =
        (prefs.knownDevices() + history.knownAddresses()).distinct()
            .sortedByDescending { history.last(it)?.time ?: 0L }

    fun clearHistory(address: String) {
        history.clear(address)
        if (conversation == address) {
            messages.clear()
            ui?.onConversationChanged()
        }
    }

    fun sendText(text: String): Boolean {
        val address = peerAddress
        val b = bt
        if (!canSend || address == null || b == null) {
            notice("אין חיבור פעיל")
            return false
        }
        val msg = ChatMessage(
            ids.incrementAndGet(), true, MsgKind.TEXT, System.currentTimeMillis(),
            text = text, status = MsgStatus.PENDING
        )
        show(address, msg)
        b.sendText(text) { ok ->
            post {
                msg.status = if (ok) MsgStatus.DONE else MsgStatus.FAILED
                history.append(address, msg)
                changed(msg, progressOnly = false)
                if (!ok) notice("ההודעה לא נשלחה")
            }
        }
        return true
    }

    fun sendFiles(uris: List<Uri>, reduceImages: Boolean) {
        val address = peerAddress
        if (!canSend || address == null) {
            notice("אין חיבור פעיל")
            return
        }
        for (uri in uris) prep.execute { prepareAndSend(address, uri, reduceImages) }
    }

    // ---------------------------------------------------------------- sending files (background)

    private fun prepareAndSend(address: String, uri: Uri, reduce: Boolean) {
        val info = try {
            FileStore.queryInfo(app, uri)
        } catch (_: Exception) {
            post { notice("לא ניתן לקרוא את הקובץ") }
            return
        }
        var sendUri = uri
        var name = info.name
        var size = info.size
        var tempFile: File? = null // reduced / copied version, deleted after sending

        if (reduce && ImageTools.isImage(app, uri, name)) {
            val small = try { ImageTools.reduce(app, uri, name) } catch (_: Throwable) { null }
            if (small != null && (size < 0 || small.length() < size)) {
                sendUri = providerUri(small)
                name = small.name
                size = small.length()
                tempFile = small
            } else {
                small?.parentFile?.deleteRecursively()
            }
        }

        if (size < 0) { // unknown size - copy to cache first so the frame length is known
            try {
                val dir = File(app.cacheDir, "outgoing/${System.currentTimeMillis()}").apply { mkdirs() }
                val copy = File(dir, FileStore.sanitize(name))
                app.contentResolver.openInputStream(sendUri)?.use { input ->
                    copy.outputStream().use { input.copyTo(it) }
                } ?: throw IOException()
                sendUri = providerUri(copy)
                size = copy.length()
                tempFile = copy
            } catch (_: Exception) {
                post { notice("לא ניתן לקרוא את הקובץ $name") }
                return
            }
        }
        if (size > BtProtocol.MAX_FILE_SIZE) {
            post { notice("$name גדול מדי (המקסימום 2GB)") }
            return
        }

        val msg = ChatMessage(
            ids.incrementAndGet(), true, MsgKind.FILE, System.currentTimeMillis(),
            fileName = name, fileUri = uri.toString(), fileSize = size, status = MsgStatus.PENDING
        )
        post { show(address, msg) }

        val total = size
        val finalUri = sendUri
        val temp = tempFile
        var lastPercent = -1
        val b = bt ?: return
        b.sendFile(
            name, total,
            open = { app.contentResolver.openInputStream(finalUri) ?: throw IOException("no stream") },
            progress = { sent ->
                val p = percent(sent, total)
                if (p != lastPercent) {
                    lastPercent = p
                    post { msg.progress = p; changed(msg, progressOnly = true) }
                }
            },
            onWifi = { post { msg.viaWifi = true; changed(msg, progressOnly = false) } },
            done = { ok ->
                temp?.parentFile?.deleteRecursively()
                post {
                    msg.status = if (ok) MsgStatus.DONE else MsgStatus.FAILED
                    history.append(address, msg)
                    changed(msg, progressOnly = false)
                    if (!ok) notice("שליחת $name נכשלה")
                }
            }
        )
    }

    private fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(app, "${app.packageName}.files", file)

    // ---------------------------------------------------------------- temporary files

    /** Photos taken from the chat and leftover copies prepared for sending. */
    private fun tempDirs() = listOf(File(app.cacheDir, "camera"), File(app.cacheDir, "outgoing"))

    fun tempFilesSize(): Long =
        tempDirs().sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    fun clearTempFiles() {
        tempDirs().forEach { it.deleteRecursively() }
    }

    // ---------------------------------------------------------------- BluetoothService callbacks (background threads)

    override fun onListenFailed(message: String) = post {
        if (adapter?.isEnabled != true) setState(State.BT_OFF)
        main.removeCallbacks(retryListen)
        main.postDelayed(retryListen, 4000)
    }

    override fun onIncomingRequest(device: BluetoothDevice) = post {
        peerAddress = device.address
        cancelling = false
        setState(State.INCOMING_REQUEST)
        if (prefs.isTrusted(device.address)) {
            respondToIncoming(accept = true, remember = false)
            return@post
        }
        main.postDelayed(requestTimeout, 60_000)
        if (uiVisible) ui?.onIncomingRequest()
        else Notifications.request(app, displayName(device.address))
    }

    override fun onAwaitingApproval(device: BluetoothDevice) = post {
        if (state == State.CONNECTING) setState(State.AWAITING_APPROVAL)
    }

    override fun onConnected(device: BluetoothDevice) = post {
        if (state == State.AWAITING_APPROVAL || state == State.CONNECTING) markConnected(device.address)
    }

    override fun onConnectFailed(device: BluetoothDevice, message: String) = post {
        if (state != State.CONNECTING) return@post
        if (!cancelling) {
            notice(
                "לא ניתן להתחבר אל ${displayName(device.address)}.\n" +
                    "ודא ש-BT Chat פתוח במכשיר השני ושה-Bluetooth פעיל."
            )
        }
        afterConnectionEnded()
    }

    override fun onRejected(device: BluetoothDevice) = post {
        cancelling = true
        notice("${displayName(device.address)} דחה את בקשת החיבור")
    }

    override fun onDisconnected(device: BluetoothDevice) = post {
        val was = state
        if (was == State.INCOMING_REQUEST) {
            main.removeCallbacks(requestTimeout)
            Notifications.cancelRequest(app)
        }
        if (!cancelling) {
            when (was) {
                State.CONNECTED -> notice("החיבור עם ${displayName(device.address)} נותק")
                State.AWAITING_APPROVAL -> notice("החיבור נסגר לפני שאושר")
                else -> Unit
            }
        }
        if (was != State.LISTENING && was != State.BT_OFF) afterConnectionEnded()
    }

    override fun onNotice(text: String) = post { notice(text) }

    override fun onTextReceived(text: String) = post {
        val address = peerAddress ?: return@post
        val msg = ChatMessage(ids.incrementAndGet(), false, MsgKind.TEXT, System.currentTimeMillis(), text = text)
        history.append(address, msg)
        show(address, msg)
        notifyIncoming(address, text)
    }

    override fun onFileIncoming(fileName: String, size: Long, viaWifi: Boolean): BluetoothService.IncomingFile? {
        val address = peerAddress ?: return null
        val target = try {
            fileStore.create(fileName)
        } catch (e: Exception) {
            post { notice("לא ניתן לשמור את $fileName: ${e.message}") }
            return null
        }
        val msg = ChatMessage(
            ids.incrementAndGet(), false, MsgKind.FILE, System.currentTimeMillis(),
            fileName = target.finalName, fileUri = target.uri.toString(), fileSize = size,
            location = target.location, status = MsgStatus.PENDING, viaWifi = viaWifi
        )
        post { show(address, msg) }

        return object : BluetoothService.IncomingFile {
            override val output: OutputStream = target.output
            private var lastPercent = -1

            override fun onProgress(received: Long) {
                val p = percent(received, size)
                if (p != lastPercent) {
                    lastPercent = p
                    post { msg.progress = p; changed(msg, progressOnly = true) }
                }
            }

            override fun onFinished(success: Boolean) {
                fileStore.complete(target, success)
                post {
                    msg.status = if (success) MsgStatus.DONE else MsgStatus.FAILED
                    if (!success) msg.fileUri = null
                    history.append(address, msg)
                    changed(msg, progressOnly = false)
                    if (success) notifyIncoming(address, "📎 ${msg.fileName}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun markConnected(address: String) {
        prefs.addKnownDevice(address)
        peerAddress = address
        cancelling = false
        if (conversation != address) openConversation(address)
        setState(State.CONNECTED)
    }

    private fun afterConnectionEnded() {
        peerAddress = null
        cancelling = false
        setState(State.LISTENING)
        val next = pendingConnect
        pendingConnect = null
        if (next != null) {
            connect(next)
            return
        }
        main.removeCallbacks(retryListen)
        main.postDelayed(retryListen, 400)
    }

    private fun show(address: String, msg: ChatMessage) {
        if (conversation != address) return
        messages.add(msg)
        ui?.onMessageAdded(messages.size - 1)
    }

    private fun changed(msg: ChatMessage, progressOnly: Boolean) {
        val index = messages.indexOf(msg)
        if (index >= 0) ui?.onMessageChanged(index, progressOnly)
    }

    private fun notifyIncoming(address: String, text: String) {
        if (!prefs.notificationsEnabled) return
        if (uiVisible && conversation == address) return
        Notifications.message(app, address, displayName(address), text)
    }

    private fun notice(text: String) {
        ui?.onNotice(text)
    }

    private fun setState(newState: State) {
        state = newState
        ui?.onStateChanged()
        ChatService.update(app, statusText())
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun percent(done: Long, total: Long): Int =
        if (total <= 0) 100 else (done * 100 / total).toInt().coerceIn(0, 100)
}
