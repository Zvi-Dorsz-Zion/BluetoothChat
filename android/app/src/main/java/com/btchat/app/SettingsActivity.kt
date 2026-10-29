package com.btchat.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.btchat.app.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

private const val DIRECT_SUMMARY =
    "כשהטלפון והמחשב לא על אותה רשת, הטלפון פותח לרגע נקודה חמה פרטית והמחשב מתחבר אליה, " +
        "רק לקבצים מעל 5MB. המחשב מתנתק לזמן ההעברה מהרשת הרגילה שלו וחוזר אליה לבד. " +
        "דורש הרשאת מיקום (ובאנדרואיד 12 ומטה גם מיקום פועל)."

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val chat get() = (requireActivity().application as BtChatApp).controller

        private val directPermission = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { }

        private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    chat.prefs.saveTreeUri = uri.toString()
                } catch (_: SecurityException) {
                }
                refresh()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings, rootKey)

            findPreference<Preference>("save_folder")?.setOnPreferenceClickListener {
                pickFolder.launch(null)
                true
            }
            findPreference<Preference>("save_folder_reset")?.setOnPreferenceClickListener {
                chat.prefs.saveTreeUri = null
                refresh()
                true
            }
            findPreference<Preference>("trusted_screen")?.setOnPreferenceClickListener {
                showTrusted()
                true
            }
            findPreference<Preference>("direct_network")?.setOnPreferenceChangeListener { _, value ->
                if (value != "off") askDirectPermission()
                true
            }
            findPreference<ListPreference>("direct_network")?.summaryProvider =
                Preference.SummaryProvider<ListPreference> { p -> "${p.entry}\n$DIRECT_SUMMARY" }
            findPreference<Preference>("temp_files")?.setOnPreferenceClickListener {
                confirmClearTemp()
                true
            }
            findPreference<Preference>("about")?.summary = try {
                val ctx = requireContext()
                "גרסה " + ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            } catch (_: Exception) {
                ""
            }
            refresh()
        }

        private fun refresh() {
            findPreference<Preference>("save_folder")?.summary = chat.fileStore.folderLabel()
            findPreference<Preference>("save_folder_reset")?.isVisible = chat.prefs.saveTreeUri != null
            val count = chat.prefs.trustedDevices().size
            findPreference<Preference>("trusted_screen")?.summary =
                if (count == 0) "אין. כל חיבור נכנס דורש אישור." else "$count מכשירים מתחברים בלי לשאול"
            findPreference<Preference>("temp_files")?.summary =
                "תמונות שצילמת מהצ'אט ושלחת: ${MessageAdapter.formatSize(chat.tempFilesSize())}"
        }

        private fun askDirectPermission() {
            val permissions =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            val ctx = requireContext()
            if (permissions.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }) {
                directPermission.launch(permissions)
            }
        }

        private fun confirmClearTemp() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("ניקוי קבצים זמניים")
                .setMessage(
                    "יימחקו התמונות שצילמת מתוך הצ'אט (${MessageAdapter.formatSize(chat.tempFilesSize())}).\n" +
                        "הן כבר נשלחו, והעותק נמצא אצל המקבל. בהיסטוריה אצלך הן יופיעו כ\"לא זמין\".\n" +
                        "תמונות ששלחת מהגלריה לא נמחקות - הן לא שוכפלו."
                )
                .setPositiveButton("מחק") { _, _ ->
                    chat.clearTempFiles()
                    refresh()
                }
                .setNegativeButton("ביטול", null)
                .show()
        }

        private fun showTrusted() {
            val trusted = chat.prefs.trustedDevices().toList()
            if (trusted.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("מכשירים שמאושרים תמיד")
                    .setMessage("אין מכשירים כאלה.\nכשמכשיר מבקש להתחבר, סמן \"אשר תמיד\" כדי להוסיף אותו.")
                    .setPositiveButton("סגור", null)
                    .show()
                return
            }
            val names = trusted.map { "${chat.displayName(it)}  ($it)" }.toTypedArray()
            val remove = BooleanArray(trusted.size)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("סמן מכשירים להסרה")
                .setMultiChoiceItems(names, remove) { _, which, checked -> remove[which] = checked }
                .setPositiveButton("הסר מסומנים") { _, _ ->
                    trusted.forEachIndexed { i, address -> if (remove[i]) chat.prefs.setTrusted(address, false) }
                    refresh()
                }
                .setNegativeButton("ביטול", null)
                .show()
        }
    }
}
