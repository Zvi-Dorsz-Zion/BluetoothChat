package com.btchat.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.btchat.app.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity(), BluetoothService.Listener {

    companion object {
        private const val CHANNEL_ID = "btchat_messages"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: BluetoothAdapter
    private lateinit var btService: BluetoothService

    @Volatile private var inForeground = false

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: notifications are optional */ }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            Toast.makeText(this, "הרשאות אושרו", Toast.LENGTH_SHORT).show()
            requestNotificationPermissionIfNeeded()
        } else {
            Toast.makeText(this, "יש לאשר הרשאות בלוטוס כדי להמשיך", Toast.LENGTH_LONG).show()
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val name = queryFileName(uri) ?: "file_${System.currentTimeMillis()}"
                btService.sendFile(name, bytes)
                appendChat("אני (קובץ): $name")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createNotificationChannel()

        val manager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        adapter = manager.adapter ?: run {
            Toast.makeText(this, "המכשיר לא תומך בבלוטוס", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        btService = BluetoothService(adapter, this)
        if (ensurePermissions()) requestNotificationPermissionIfNeeded()

        binding.btnListen.setOnClickListener {
            if (ensurePermissions()) {
                setStatus("ממתין לחיבור נכנס...")
                btService.startServer()
            }
        }

        binding.btnPickDevice.setOnClickListener {
            if (ensurePermissions()) showPairedDevicesDialog()
        }

        binding.btnSend.setOnClickListener {
            val text = binding.editMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                btService.sendText(text)
                appendChat("אני: $text")
                binding.editMessage.text.clear()
            }
        }

        binding.btnAttach.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }
    }

    override fun onStart() {
        super.onStart()
        inForeground = true
    }

    override fun onStop() {
        super.onStop()
        inForeground = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "הודעות צ'אט", NotificationManager.IMPORTANCE_HIGH
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(title: String, body: String) {
        if (inForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(this)
            .notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun ensurePermissions(): Boolean {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isEmpty()) {
            true
        } else {
            permissionLauncher.launch(missing.toTypedArray())
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevicesDialog() {
        val paired = adapter.bondedDevices.toList()
        if (paired.isEmpty()) {
            Toast.makeText(this, "אין מכשירים מזווגים. זווג קודם דרך הגדרות בלוטוס.", Toast.LENGTH_LONG).show()
            return
        }
        val names = paired.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("בחר מכשיר מזווג")
            .setItems(names) { _, which ->
                val device: BluetoothDevice = paired[which]
                setStatus("מתחבר אל ${device.name}...")
                btService.connectToDevice(device)
            }
            .show()
    }

    private fun queryFileName(uri: android.net.Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return null
    }

    private fun appendChat(line: String) = runOnUiThread {
        binding.txtChat.append("$line\n")
    }

    private fun setStatus(text: String) = runOnUiThread {
        binding.txtStatus.text = text
    }

    // ---- BluetoothService.Listener callbacks ----

    override fun onConnected(deviceName: String) {
        setStatus("מחובר אל: $deviceName")
    }

    override fun onDisconnected() {
        setStatus("מנותק")
    }

    override fun onTextReceived(text: String) {
        appendChat("הם: $text")
        showNotification("הודעה חדשה", text)
    }

    override fun onFileReceived(fileName: String, bytes: ByteArray) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val outFile = File(dir, fileName)
        FileOutputStream(outFile).use { it.write(bytes) }
        appendChat("הם (קובץ): $fileName נשמר ב-${outFile.absolutePath}")
        showNotification("התקבל קובץ", fileName)
    }

    override fun onError(message: String) {
        setStatus("שגיאה")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        btService.stop()
    }
}
