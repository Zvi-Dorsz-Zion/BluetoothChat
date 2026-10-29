package com.btchat.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.btchat.app.databinding.ActivityDevicesBinding
import com.btchat.app.databinding.ItemDeviceBinding
import com.btchat.app.databinding.ItemHeaderBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/** Known devices (paired / with history) + searching and pairing new ones. */
@SuppressLint("MissingPermission")
class DevicesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_CONNECT = "connect"
    }

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Device(
            val address: String,
            val title: String,
            val subtitle: String,
            val known: Boolean,
            val connected: Boolean,
            /** Paired in the system but never used with BT Chat - tapping connects (no pairing needed). */
            val paired: Boolean = false
        ) : Row()
    }

    private lateinit var binding: ActivityDevicesBinding
    private lateinit var chat: ChatController
    private val rows = ArrayList<Row>()
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private var scanning = false
    /** Show the "other paired devices" section (after the user pressed search). */
    private var showOthers = false
    private var bondingAddress: String? = null
    private val listAdapter = DevicesAdapter()

    private val scanPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (canScan()) startScan() else notice("בלי הרשאה אי אפשר לחפש מכשירים חדשים") }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    if (device != null && device.bondState != BluetoothDevice.BOND_BONDED) {
                        found[device.address] = device
                        rebuild()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    updateScanUi()
                    rebuild()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    if (device == null || device.address != bondingAddress) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                        BluetoothDevice.BOND_BONDED -> {
                            bondingAddress = null
                            finishWith(device.address, connect = true)
                        }
                        BluetoothDevice.BOND_NONE -> {
                            bondingAddress = null
                            notice("הצימוד נכשל או בוטל")
                            rebuild()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevicesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        chat = (application as BtChatApp).controller

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = listAdapter
        binding.btnScan.setOnClickListener {
            showOthers = true
            if (scanning) stopScan() else startScan()
            rebuild()
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        rebuild()
        updateScanUi()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        if (scanning) runCatching { chat.adapter?.cancelDiscovery() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------------------------------------------------------- list

    @SuppressLint("NotifyDataSetChanged")
    private fun rebuild() {
        rows.clear()
        val bonded = runCatching { chat.adapter?.bondedDevices?.toList() }.getOrNull() ?: emptyList()
        val bondedAddresses = bonded.map { it.address }.toSet()
        val known = chat.knownDevices()

        rows += Row.Header(if (known.isEmpty()) "עוד לא התחברת לאף מכשיר" else "המכשירים שלי")
        for (address in known) {
            val last = chat.history.last(address)
            val preview = when {
                last == null -> if (address in bondedAddresses) "מזווג · $address" else "לא מזווג · $address"
                last.kind == MsgKind.FILE -> "📎 ${last.fileName}"
                else -> last.text ?: ""
            }
            val connected = chat.state == ChatController.State.CONNECTED && chat.peerAddress == address
            rows += Row.Device(address, chat.displayName(address), preview, known = true, connected = connected)
        }

        val others = bonded.filter { it.address !in known }
        if ((showOthers || known.isEmpty()) && others.isNotEmpty()) {
            rows += Row.Header("מכשירים מזווגים נוספים")
            for (d in others) {
                rows += Row.Device(
                    d.address, chat.displayName(d.address), "מזווג · הקש כדי להתחבר",
                    known = false, connected = false, paired = true
                )
            }
        }

        if (scanning || found.isNotEmpty()) {
            rows += Row.Header(if (scanning) "מחפש מכשירים בסביבה..." else "נמצאו בסביבה")
            for ((address, device) in found) {
                val subtitle = if (address == bondingAddress) "מצמד... אשר את הקוד בשני המכשירים"
                else "הקש כדי לצמד · $address"
                rows += Row.Device(address, device.name ?: address, subtitle, known = false, connected = false)
            }
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun onDeviceClick(row: Row.Device) {
        if (row.known || row.paired) {
            finishWith(row.address, connect = true)
            return
        }
        val device = found[row.address] ?: return
        runCatching { chat.adapter?.cancelDiscovery() }
        bondingAddress = row.address
        if (!device.createBond()) {
            bondingAddress = null
            notice("לא ניתן להתחיל צימוד")
        }
        rebuild()
    }

    private fun onDeviceLongClick(row: Row.Device) {
        if (!row.known) return
        MaterialAlertDialogBuilder(this)
            .setTitle(row.title)
            .setItems(arrayOf("התחבר", "הצג היסטוריה (בלי להתחבר)", "שנה שם", "מחק היסטוריה", "הסר מהרשימה")) { _, which ->
                when (which) {
                    0 -> finishWith(row.address, connect = true)
                    1 -> finishWith(row.address, connect = false)
                    2 -> Dialogs.rename(this, chat, row.address) { rebuild() }
                    3 -> Dialogs.confirmClearHistory(this, chat, row.address) { rebuild() }
                    4 -> confirmRemove(row)
                }
            }
            .show()
    }

    private fun confirmRemove(row: Row.Device) {
        MaterialAlertDialogBuilder(this)
            .setTitle("הסרה מהרשימה")
            .setMessage("להסיר את ${row.title} מהרשימה? גם ההיסטוריה איתו תימחק.\nהצימוד במערכת לא משתנה, ואפשר להתחבר אליו שוב דרך \"חפש מכשירים חדשים\".")
            .setPositiveButton("הסר") { _, _ ->
                chat.prefs.removeKnownDevice(row.address)
                chat.clearHistory(row.address)
                rebuild()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun finishWith(address: String, connect: Boolean) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ADDRESS, address).putExtra(EXTRA_CONNECT, connect))
        finish()
    }

    // ---------------------------------------------------------------- scanning

    private fun canScan(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) granted(Manifest.permission.BLUETOOTH_SCAN)
        else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startScan() {
        val adapter = chat.adapter ?: return
        if (!adapter.isEnabled) {
            notice("יש להפעיל Bluetooth")
            return
        }
        if (!canScan()) {
            scanPermission.launch(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val lm = getSystemService(LocationManager::class.java)
            if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("נדרש מיקום")
                    .setMessage("באנדרואיד 10-11 חיפוש מכשירי Bluetooth עובד רק כשהמיקום (GPS) מופעל.")
                    .setPositiveButton("פתח הגדרות מיקום") { _, _ ->
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                    .setNegativeButton("ביטול", null)
                    .show()
                return
            }
        }
        found.clear()
        showOthers = true
        scanning = adapter.startDiscovery()
        if (!scanning) notice("לא ניתן להתחיל חיפוש")
        updateScanUi()
        rebuild()
    }

    private fun stopScan() {
        runCatching { chat.adapter?.cancelDiscovery() }
        scanning = false
        updateScanUi()
        rebuild()
    }

    private fun updateScanUi() {
        binding.btnScan.text = if (scanning) "עצור חיפוש" else "חפש מכשירים חדשים"
        binding.scanProgress.isVisible = scanning
    }

    private fun notice(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- adapter

    private inner class DevicesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class HeaderHolder(val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root)
        inner class DeviceHolder(val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderHolder(ItemHeaderBinding.inflate(inflater, parent, false))
            else DeviceHolder(ItemDeviceBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).b.root.text = row.title
                is Row.Device -> {
                    val b = (holder as DeviceHolder).b
                    b.avatar.text = row.title.trim().firstOrNull()?.uppercase() ?: "?"
                    b.avatar.setBackgroundResource(if (row.known) R.drawable.bg_avatar else R.drawable.bg_avatar_new)
                    b.name.text = row.title
                    b.subtitle.text = row.subtitle
                    b.connectedDot.isVisible = row.connected
                    b.root.setOnClickListener { onDeviceClick(row) }
                    b.root.setOnLongClickListener { onDeviceLongClick(row); true }
                }
            }
        }
    }
}
