package com.btchat.app

import android.app.Application

class BtChatApp : Application() {
    /** Lives as long as the process, so the connection survives rotation and hidden screens. */
    lateinit var controller: ChatController
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        controller = ChatController(this)
    }
}
