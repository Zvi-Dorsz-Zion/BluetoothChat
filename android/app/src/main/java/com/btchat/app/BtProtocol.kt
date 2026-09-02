package com.btchat.app

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * BT-Chat Protocol v1 (see /PROTOCOL.md in repo root).
 * Frame = [1 byte type][4 bytes length][payload]
 */
object BtProtocol {
    // Standard SPP UUID - MUST match the Windows side exactly.
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val TYPE_TEXT: Byte = 0x01
    const val TYPE_FILE: Byte = 0x02

    fun sendText(out: OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        writeFrame(out, TYPE_TEXT, payload)
    }

    fun sendFile(out: OutputStream, fileName: String, fileBytes: ByteArray) {
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val buffer = ByteArrayOutputStream()
        val dos = DataOutputStream(buffer)
        dos.writeShort(nameBytes.size)
        dos.write(nameBytes)
        dos.write(fileBytes)
        writeFrame(out, TYPE_FILE, buffer.toByteArray())
    }

    private fun writeFrame(out: OutputStream, type: Byte, payload: ByteArray) {
        val dos = DataOutputStream(out)
        synchronized(out) {
            dos.writeByte(type.toInt())
            dos.writeInt(payload.size)
            dos.write(payload)
            dos.flush()
        }
    }

    sealed class Message {
        data class Text(val text: String) : Message()
        data class FileReceived(val fileName: String, val bytes: ByteArray) : Message()
    }

    /** Blocking read of exactly one frame. Throws on stream close/error. */
    fun readNextMessage(input: InputStream): Message {
        val dis = DataInputStream(input)
        val type = dis.readByte()
        val length = dis.readInt()
        val payload = ByteArray(length)
        dis.readFully(payload)

        return when (type) {
            TYPE_TEXT -> Message.Text(String(payload, Charsets.UTF_8))
            TYPE_FILE -> {
                val nameLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                val fileName = String(payload, 2, nameLen, Charsets.UTF_8)
                val fileBytes = payload.copyOfRange(2 + nameLen, payload.size)
                Message.FileReceived(fileName, fileBytes)
            }
            else -> throw IllegalStateException("Unknown frame type: $type")
        }
    }
}
