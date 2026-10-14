package com.btchat.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the process (and the Bluetooth connection held
 * by [ChatController]) alive while the app is hidden or the screen rotates.
 */
class ChatService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chat = (application as BtChatApp).controller
        when (intent?.action) {
            ACTION_ACCEPT -> chat.respondToIncoming(accept = true, remember = false)
            ACTION_REJECT -> chat.respondToIncoming(accept = false, remember = false)
            ACTION_EXIT -> {
                chat.shutdown()
                return START_NOT_STICKY
            }
        }
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_SERVICE,
                Notifications.service(this, chat.statusText()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            running = true
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_ACCEPT = "com.btchat.app.ACCEPT"
        const val ACTION_REJECT = "com.btchat.app.REJECT"
        const val ACTION_EXIT = "com.btchat.app.EXIT"

        @Volatile private var running = false

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ChatService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChatService::class.java))
        }

        @android.annotation.SuppressLint("MissingPermission")
        fun update(context: Context, text: String) {
            if (!running) return
            try {
                NotificationManagerCompat.from(context)
                    .notify(Notifications.ID_SERVICE, Notifications.service(context, text))
            } catch (_: SecurityException) {
            }
        }
    }
}
