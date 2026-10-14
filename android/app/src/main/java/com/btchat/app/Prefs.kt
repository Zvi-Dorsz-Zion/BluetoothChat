package com.btchat.app

import android.content.Context
import androidx.preference.PreferenceManager

/** App settings. Keys are shared with res/xml/settings.xml. */
class Prefs(context: Context) {
    private val sp = PreferenceManager.getDefaultSharedPreferences(context)

    var notificationsEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = sp.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    /** SAF tree uri of a folder the user picked, or null for Downloads/BT Chat. */
    var saveTreeUri: String?
        get() = sp.getString(KEY_SAVE_TREE, null)
        set(value) = sp.edit().putString(KEY_SAVE_TREE, value).apply()

    /** "ask", "reduced" or "original". */
    val imageQuality: String
        get() = sp.getString(KEY_IMAGE_QUALITY, "ask") ?: "ask"

    var lastAddress: String?
        get() = sp.getString(KEY_LAST_ADDRESS, null)
        set(value) = sp.edit().putString(KEY_LAST_ADDRESS, value).apply()

    fun trustedDevices(): Set<String> = sp.getStringSet(KEY_TRUSTED, emptySet())?.toSet() ?: emptySet()

    fun isTrusted(address: String) = address in trustedDevices()

    fun setTrusted(address: String, trusted: Boolean) {
        val set = trustedDevices().toMutableSet()
        if (trusted) set.add(address) else set.remove(address)
        sp.edit().putStringSet(KEY_TRUSTED, set).apply()
    }

    fun nickname(address: String): String? = sp.getString(KEY_NICK_PREFIX + address, null)

    fun setNickname(address: String, name: String?) {
        if (name.isNullOrBlank()) sp.edit().remove(KEY_NICK_PREFIX + address).apply()
        else sp.edit().putString(KEY_NICK_PREFIX + address, name.trim()).apply()
    }

    companion object {
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_SAVE_TREE = "save_tree_uri"
        const val KEY_IMAGE_QUALITY = "image_quality"
        const val KEY_LAST_ADDRESS = "last_address"
        const val KEY_TRUSTED = "trusted_devices"
        private const val KEY_NICK_PREFIX = "nick_"
    }
}
