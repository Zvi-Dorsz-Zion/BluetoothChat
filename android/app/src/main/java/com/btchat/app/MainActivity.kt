package com.btchat.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.btchat.app.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), ChatController.Ui {

    private lateinit var binding: ActivityMainBinding
    private lateinit var chat: ChatController
    private lateinit var adapter: MessageAdapter
    private var requestDialog: AlertDialog? = null
    private var pendingShare: List<Uri> = emptyList()
    private var cameraUri: Uri? = null
    private var askedPermissionsOnce = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasBluetoothPermission()) chat.start()
        else if (askedPermissionsOnce) openAppSettings()
        askedPermissionsOnce = true
        refreshAll()
    }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { chat.ensureListening() }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { keepAccess(it) }
            sendWithQuality(uris)
        }
    }

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = cameraUri
        if (ok && uri != null) sendWithQuality(listOf(uri))
    }

    private val pickDevice = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult
        val address = data.getStringExtra(DevicesActivity.EXTRA_ADDRESS) ?: return@registerForActivityResult
        if (data.getBooleanExtra(DevicesActivity.EXTRA_CONNECT, true)) chat.connect(address)
        else chat.openConversation(address)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        chat = (application as BtChatApp).controller
        cameraUri = savedInstanceState?.getString(KEY_CAMERA_URI)?.let { Uri.parse(it) }

        adapter = MessageAdapter(this, chat.messages, ::onMessageClick, ::onMessageLongClick)
        binding.recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recycler.adapter = adapter

        binding.btnSend.setOnClickListener { sendCurrentText() }
        binding.btnAttach.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        binding.btnCamera.setOnClickListener { openCamera() }
        binding.bannerButton.setOnClickListener { onBannerAction() }
        binding.editMessage.addTextChangedListener { updateInput() }

        handleShareIntent(intent)
        if (hasBluetoothPermission()) chat.start()
        if (savedInstanceState == null) requestMissingPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        chat.ui = this
        chat.uiVisible = true
        if (hasBluetoothPermission()) chat.start()
        chat.conversation?.let { com.btchat.app.Notifications.cancelMessage(this, it) }
        refreshAll()
        if (chat.state == ChatController.State.INCOMING_REQUEST) onIncomingRequest()
    }

    override fun onStop() {
        super.onStop()
        chat.uiVisible = false
        if (chat.ui === this) chat.ui = null
        requestDialog?.dismiss()
        requestDialog = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraUri?.let { outState.putString(KEY_CAMERA_URI, it.toString()) }
    }

    // ---------------------------------------------------------------- permissions

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filterNot { granted(it) }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun openAppSettings() {
        Snackbar.make(binding.root, "יש לאשר את הרשאת \"מכשירים בקרבת מקום\" בהגדרות", Snackbar.LENGTH_LONG).show()
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    // ---------------------------------------------------------------- UI refresh

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshAll() {
        adapter.notifyDataSetChanged()
        updateHeader()
        updateBanner()
        updateInput()
        updateEmpty()
        scrollToBottom()
    }

    private fun updateHeader() {
        val conversation = chat.conversation
        supportActionBar?.title = conversation?.let { chat.displayName(it) } ?: "BT Chat"
        supportActionBar?.subtitle = chat.statusText()
        invalidateOptionsMenu()
    }

    private fun updateBanner() {
        val state = chat.state
        val (action, colorRes) = when (state) {
            ChatController.State.NEED_PERMISSION -> "אשר הרשאה" to R.color.banner_warn
            ChatController.State.NO_BLUETOOTH -> null to R.color.banner_warn
            ChatController.State.BT_OFF -> "הפעל" to R.color.banner_warn
            ChatController.State.LISTENING -> "התחבר למכשיר" to R.color.banner_neutral
            ChatController.State.CONNECTING,
            ChatController.State.AWAITING_APPROVAL -> "ביטול" to R.color.banner_neutral
            ChatController.State.INCOMING_REQUEST -> "הצג" to R.color.banner_warn
            ChatController.State.CONNECTED -> "התנתק" to R.color.banner_ok
        }
        binding.bannerText.text = when (state) {
            ChatController.State.LISTENING -> "מוכן לקבל חיבורים · אפשר גם להתחבר מכאן"
            else -> chat.statusText()
        }
        binding.bannerButton.isVisible = action != null
        binding.bannerButton.text = action ?: ""
        binding.banner.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        binding.bannerProgress.isVisible = state == ChatController.State.CONNECTING ||
            state == ChatController.State.AWAITING_APPROVAL
        binding.bannerDot.isVisible = !binding.bannerProgress.isVisible
        binding.bannerDot.setBackgroundResource(
            if (state == ChatController.State.CONNECTED) R.drawable.dot_on else R.drawable.dot_off
        )
    }

    private fun updateInput() {
        val canSend = chat.canSend
        binding.editMessage.isEnabled = canSend
        binding.editMessage.hint = when {
            canSend -> "הודעה"
            chat.state == ChatController.State.CONNECTED -> "מחובר למכשיר אחר"
            else -> "התחבר כדי לשלוח הודעות"
        }
        binding.btnAttach.isEnabled = canSend
        binding.btnCamera.isEnabled = canSend
        binding.btnAttach.alpha = if (canSend) 1f else 0.4f
        binding.btnCamera.alpha = if (canSend) 1f else 0.4f
        val sendEnabled = canSend && binding.editMessage.text.isNotBlank()
        binding.btnSend.isEnabled = sendEnabled
        binding.btnSend.alpha = if (sendEnabled) 1f else 0.4f
    }

    private fun updateEmpty() {
        binding.emptyView.isVisible = chat.messages.isEmpty()
        binding.emptyText.text = if (chat.conversation == null)
            "עוד אין שיחות.\nלחץ \"התחבר למכשיר\" כדי להתחיל, או המתן שמכשיר אחר יתחבר אליך."
        else "אין הודעות עדיין"
    }

    private fun scrollToBottom() {
        if (chat.messages.isNotEmpty()) binding.recycler.scrollToPosition(chat.messages.size - 1)
    }

    // ---------------------------------------------------------------- ChatController.Ui

    override fun onStateChanged() {
        updateHeader()
        updateBanner()
        updateInput()
        if (chat.state != ChatController.State.INCOMING_REQUEST) {
            requestDialog?.dismiss()
            requestDialog = null
        }
        if (chat.canSend && pendingShare.isNotEmpty()) {
            val uris = pendingShare
            pendingShare = emptyList()
            sendWithQuality(uris)
        }
    }

    override fun onConversationChanged() = refreshAll()

    override fun onMessageAdded(position: Int) {
        adapter.notifyItemInserted(position)
        updateEmpty()
        binding.recycler.scrollToPosition(position)
    }

    override fun onMessageChanged(position: Int, progressOnly: Boolean) {
        if (progressOnly) adapter.notifyItemChanged(position, MessageAdapter.PAYLOAD_PROGRESS)
        else adapter.notifyItemChanged(position)
    }

    override fun onIncomingRequest() {
        if (chat.state != ChatController.State.INCOMING_REQUEST) return
        if (requestDialog?.isShowing == true) return
        val address = chat.peerAddress ?: return
        val view = layoutInflater.inflate(R.layout.dialog_request, null)
        view.findViewById<TextView>(R.id.txtRequest).text =
            "${chat.displayName(address)} ($address) מבקש להתחבר אליך."
        val remember = view.findViewById<CheckBox>(R.id.checkRemember)
        requestDialog = MaterialAlertDialogBuilder(this)
            .setTitle("בקשת חיבור")
            .setView(view)
            .setCancelable(false)
            .setPositiveButton("אשר") { _, _ -> chat.respondToIncoming(true, remember.isChecked) }
            .setNegativeButton("דחה") { _, _ -> chat.respondToIncoming(false, false) }
            .show()
    }

    override fun onNotice(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.inputBar)
            .setTextMaxLines(4)
            .show()
    }

    override fun onExit() {
        finishAndRemoveTask()
    }

    // ---------------------------------------------------------------- actions

    private fun onBannerAction() {
        when (chat.state) {
            ChatController.State.NEED_PERMISSION -> {
                askedPermissionsOnce = true
                requestMissingPermissions()
            }
            ChatController.State.BT_OFF -> try {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            ChatController.State.LISTENING -> openDevices()
            ChatController.State.CONNECTING,
            ChatController.State.AWAITING_APPROVAL,
            ChatController.State.CONNECTED -> chat.disconnect()
            ChatController.State.INCOMING_REQUEST -> onIncomingRequest()
            ChatController.State.NO_BLUETOOTH -> Unit
        }
    }

    private fun openDevices() {
        if (!hasBluetoothPermission()) {
            requestMissingPermissions()
            return
        }
        pickDevice.launch(Intent(this, DevicesActivity::class.java))
    }

    private fun sendCurrentText() {
        val text = binding.editMessage.text.toString().trim()
        if (text.isEmpty()) return
        if (chat.sendText(text)) binding.editMessage.text.clear()
    }

    private fun openCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "IMG_$stamp.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        cameraUri = uri
        try {
            takePicture.launch(uri)
        } catch (_: ActivityNotFoundException) {
            onNotice("לא נמצאה אפליקציית מצלמה")
        }
    }

    /** Asks about photo quality (unless set in settings) and sends. */
    private fun sendWithQuality(uris: List<Uri>) {
        if (!chat.canSend) {
            pendingShare = uris
            onNotice("התחבר למכשיר והקבצים יישלחו אוטומטית")
            return
        }
        val images = uris.count { ImageTools.isImage(this, it, null) }
        if (images == 0) {
            chat.sendFiles(uris, reduceImages = false)
            return
        }
        when (chat.prefs.imageQuality) {
            "original" -> chat.sendFiles(uris, reduceImages = false)
            "reduced" -> chat.sendFiles(uris, reduceImages = true)
            else -> MaterialAlertDialogBuilder(this)
                .setTitle(if (images == 1) "שליחת תמונה" else "שליחת $images תמונות")
                .setItems(arrayOf("איכות מוקטנת – מהיר בהרבה (מומלץ)", "איכות מקורית")) { _, which ->
                    chat.sendFiles(uris, reduceImages = which == 0)
                }
                .setNegativeButton("ביטול", null)
                .show()
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: emptyList()
            else -> return
        }
        if (uris.isEmpty()) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { binding.editMessage.setText(it) }
        } else {
            sendWithQuality(uris)
        }
        intent.action = null // don't handle again after rotation
    }

    private fun keepAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    // ---------------------------------------------------------------- message actions

    private fun onMessageClick(m: ChatMessage) {
        if (m.kind == MsgKind.FILE) openFile(m)
    }

    private fun onMessageLongClick(m: ChatMessage) {
        if (m.kind == MsgKind.TEXT) {
            MaterialAlertDialogBuilder(this)
                .setItems(arrayOf("העתק")) { _, _ -> copy(m.text ?: "") }
                .show()
            return
        }
        val title = m.location?.let { "נשמר ב: $it" } ?: m.fileName
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(arrayOf("פתח", "שתף", "העתק שם קובץ")) { _, which ->
                when (which) {
                    0 -> openFile(m)
                    1 -> shareFile(m)
                    2 -> copy(m.fileName ?: "")
                }
            }
            .show()
    }

    private fun readableUri(m: ChatMessage): Uri? {
        val uri = m.fileUri?.let { Uri.parse(it) }
        val ok = uri != null && try {
            contentResolver.openFileDescriptor(uri, "r")?.close()
            true
        } catch (_: Exception) {
            false
        }
        if (!ok) onNotice(if (m.status == MsgStatus.PENDING) "הקובץ עוד בהעברה" else "הקובץ כבר לא זמין (אולי נמחק או הועבר)")
        return if (ok) uri else null
    }

    private fun openFile(m: ChatMessage) {
        if (m.status == MsgStatus.PENDING) {
            onNotice("הקובץ עוד בהעברה")
            return
        }
        val uri = readableUri(m) ?: return
        val mime = contentResolver.getType(uri) ?: FileStore.mimeOf(m.fileName ?: "")
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: ActivityNotFoundException) {
            onNotice("אין אפליקציה שיודעת לפתוח את הקובץ הזה")
        }
    }

    private fun shareFile(m: ChatMessage) {
        val uri = readableUri(m) ?: return
        val mime = contentResolver.getType(uri) ?: FileStore.mimeOf(m.fileName ?: "")
        val send = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "שתף"))
    }

    private fun copy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("BT Chat", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onNotice("הועתק")
    }

    // ---------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasConversation = chat.conversation != null
        menu.findItem(R.id.action_rename)?.isVisible = hasConversation
        menu.findItem(R.id.action_clear)?.isVisible = hasConversation
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val conversation = chat.conversation
        when (item.itemId) {
            R.id.action_devices -> openDevices()
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_rename -> if (conversation != null) Dialogs.rename(this, chat, conversation) { refreshAll() }
            R.id.action_clear -> if (conversation != null) Dialogs.confirmClearHistory(this, chat, conversation)
            R.id.action_exit -> MaterialAlertDialogBuilder(this)
                .setTitle("יציאה מ-BT Chat")
                .setMessage("החיבור ייסגר והאפליקציה תפסיק לקבל חיבורים עד שתיפתח שוב.")
                .setPositiveButton("יציאה") { _, _ -> chat.shutdown() }
                .setNegativeButton("ביטול", null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        private const val KEY_CAMERA_URI = "camera_uri"
    }
}
