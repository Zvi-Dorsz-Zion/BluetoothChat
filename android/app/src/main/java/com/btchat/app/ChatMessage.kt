package com.btchat.app

import org.json.JSONObject

enum class MsgKind { TEXT, FILE }

enum class MsgStatus { PENDING, DONE, FAILED }

class ChatMessage(
    val id: Long,
    val outgoing: Boolean,
    val kind: MsgKind,
    val time: Long,
    val text: String? = null,
    val fileName: String? = null,
    var fileUri: String? = null,
    val fileSize: Long = 0,
    /** Human readable place where a received file was saved, e.g. "הורדות/BT Chat/a.jpg". */
    val location: String? = null,
    var status: MsgStatus = MsgStatus.DONE,
    var progress: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("out", outgoing)
        put("kind", kind.name)
        put("time", time)
        put("status", status.name)
        put("size", fileSize)
        text?.let { put("text", it) }
        fileName?.let { put("name", it) }
        fileUri?.let { put("uri", it) }
        location?.let { put("loc", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): ChatMessage = ChatMessage(
            id = o.optLong("id"),
            outgoing = o.optBoolean("out"),
            kind = runCatching { MsgKind.valueOf(o.optString("kind")) }.getOrDefault(MsgKind.TEXT),
            time = o.optLong("time"),
            text = o.strOrNull("text"),
            fileName = o.strOrNull("name"),
            fileUri = o.strOrNull("uri"),
            fileSize = o.optLong("size"),
            location = o.strOrNull("loc"),
            status = runCatching { MsgStatus.valueOf(o.optString("status")) }.getOrDefault(MsgStatus.DONE)
        )

        private fun JSONObject.strOrNull(key: String): String? = if (has(key)) getString(key) else null
    }
}
