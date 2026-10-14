package com.btchat.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

@SuppressLint("MissingPermission")
object Notifications {
    private const val CHANNEL_MESSAGES = "btchat_messages"
    private const val CHANNEL_REQUESTS = "btchat_requests"
    private const val CHANNEL_SERVICE = "btchat_service"

    const val ID_SERVICE = 1
    private const val ID_REQUEST = 2
    private const val ID_MESSAGE_BASE = 1000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "הודעות חדשות", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REQUESTS, "בקשות חיבור", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "פעילות ברקע", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceAction(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            context, code,
            Intent(context, ChatService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun service(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle("BT Chat")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
            .addAction(0, "יציאה", serviceAction(context, ChatService.ACTION_EXIT, 3))
            .build()

    fun message(context: Context, address: String, title: String, text: String) {
        post(
            context, messageId(address),
            NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build()
        )
    }

    fun cancelMessage(context: Context, address: String) {
        NotificationManagerCompat.from(context).cancel(messageId(address))
    }

    fun request(context: Context, deviceName: String) {
        post(
            context, ID_REQUEST,
            NotificationCompat.Builder(context, CHANNEL_REQUESTS)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setContentTitle("בקשת חיבור")
                .setContentText("$deviceName מבקש להתחבר")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .setContentIntent(openApp(context))
                .addAction(0, "אשר", serviceAction(context, ChatService.ACTION_ACCEPT, 1))
                .addAction(0, "דחה", serviceAction(context, ChatService.ACTION_REJECT, 2))
                .build()
        )
    }

    fun cancelRequest(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_REQUEST)
    }

    private fun messageId(address: String) = ID_MESSAGE_BASE + (address.hashCode() and 0xFFFF)

    private fun post(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // notification permission not granted
        }
    }
}
