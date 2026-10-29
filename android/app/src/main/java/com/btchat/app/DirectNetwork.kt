package com.btchat.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Direct network": a temporary local-only hotspot opened by the phone
 * (random name + password, no internet sharing). The Windows side joins it so
 * a big file can go over Wi-Fi even when the two devices are not on a common network.
 * The phone keeps its mobile data meanwhile.
 */
class DirectNetwork(private val context: Context) {

    data class Credentials(val ssid: String, val password: String)

    class Failure(message: String) : Exception(message)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var credentials: Credentials? = null

    val isActive: Boolean get() = reservation != null

    /** Opens the hotspot (or returns the one already open). Blocks up to 20 seconds. Throws [Failure]. */
    @SuppressLint("MissingPermission")
    fun start(): Credentials {
        credentials?.let { if (reservation != null) return it }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: throw Failure("אין Wi-Fi במכשיר")
        val latch = CountDownLatch(1)
        var error: String? = null
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                reservation = r
                credentials = read(r)
                latch.countDown()
            }

            override fun onFailed(reason: Int) {
                error = when (reason) {
                    ERROR_INCOMPATIBLE_MODE -> "הטלפון לא יכול לפתוח נקודה חמה כשהוא מחובר עכשיו ל-Wi-Fi אחר"
                    ERROR_TETHERING_DISALLOWED -> "פתיחת נקודה חמה חסומה במכשיר הזה"
                    else -> "פתיחת הנקודה החמה נכשלה (קוד $reason)"
                }
                latch.countDown()
            }

            override fun onStopped() {
                reservation = null
                credentials = null
            }
        }
        try {
            main.post {
                try {
                    wifi.startLocalOnlyHotspot(callback, main)
                } catch (e: SecurityException) {
                    error = "חסרה הרשאה, או שהמיקום (GPS) כבוי - נדרש לפתיחת רשת ישירה"
                    latch.countDown()
                } catch (e: IllegalStateException) {
                    error = "נקודה חמה אחרת כבר פתוחה בטלפון"
                    latch.countDown()
                }
            }
            if (!latch.await(20, TimeUnit.SECONDS)) throw Failure("הנקודה החמה לא נפתחה בזמן")
        } catch (e: InterruptedException) {
            throw Failure("בוטל")
        }
        error?.let { throw Failure(it) }
        val c = credentials ?: throw Failure("לא התקבלו פרטי הרשת")
        Log.i(TAG, "direct network up: ${c.ssid}")
        return c
    }

    fun stop() {
        val r = reservation ?: return
        reservation = null
        credentials = null
        runCatching { r.close() }
        Log.i(TAG, "direct network closed")
    }

    @Suppress("DEPRECATION")
    private fun read(r: WifiManager.LocalOnlyHotspotReservation): Credentials? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = r.softApConfiguration
            val ssid = config.ssid
            val pass = config.passphrase
            if (ssid != null && pass != null) Credentials(ssid.trim('"'), pass) else null
        } else {
            val config = r.wifiConfiguration
            if (config?.SSID != null && config.preSharedKey != null)
                Credentials(config.SSID.trim('"'), config.preSharedKey.trim('"'))
            else null
        }

    companion object {
        const val TAG = "BtChat"
    }
}
