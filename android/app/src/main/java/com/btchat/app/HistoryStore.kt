package com.btchat.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Chat history per device: one JSON-lines file per Bluetooth address. */
class HistoryStore(context: Context) {
    private val dir = File(context.filesDir, "history").apply { mkdirs() }

    private fun file(address: String) = File(dir, address.replace(":", "") + ".jsonl")

    @Synchronized
    fun load(address: String): List<ChatMessage> {
        val f = file(address)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            if (line.isBlank()) null
            else runCatching { ChatMessage.fromJson(JSONObject(line)) }.getOrNull()
        }
    }

    @Synchronized
    fun append(address: String, message: ChatMessage) {
        file(address).appendText(message.toJson().toString() + "\n")
    }

    @Synchronized
    fun clear(address: String) {
        file(address).delete()
    }

    @Synchronized
    fun last(address: String): ChatMessage? {
        val f = file(address)
        if (!f.exists()) return null
        val line = f.readLines().lastOrNull { it.isNotBlank() } ?: return null
        return runCatching { ChatMessage.fromJson(JSONObject(line)) }.getOrNull()
    }

    /** Addresses that have a saved conversation, as "AA:BB:CC:DD:EE:FF". */
    @Synchronized
    fun knownAddresses(): List<String> =
        dir.listFiles()
            ?.filter { it.name.endsWith(".jsonl") && it.length() > 0 }
            ?.map { it.name.removeSuffix(".jsonl").chunked(2).joinToString(":") }
            ?: emptyList()
}
