package com.btchat.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * BT-Chat Protocol v2 (see /PROTOCOL.md in repo root).
 * Frame = [1 byte type][4 bytes length, big-endian][payload]
 * Must match the Windows implementation exactly.
 */
object BtProtocol {
    // Standard SPP UUID - MUST match the Windows side exactly.
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val TYPE_TEXT = 0x01
    const val TYPE_FILE = 0x02
    const val TYPE_ACCEPT = 0x04
    const val TYPE_REJECT = 0x05

    const val CHUNK = 16 * 1024
    const val MAX_FILE_SIZE = Int.MAX_VALUE - 4096L

    fun writeControl(out: DataOutputStream, type: Int) {
        out.writeByte(type)
        out.writeInt(0)
        out.flush()
    }

    fun writeText(out: DataOutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        out.writeByte(TYPE_TEXT)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    /** Streams the file straight from [input], so big files never sit fully in memory. */
    fun writeFile(
        out: DataOutputStream,
        fileName: String,
        size: Long,
        input: InputStream,
        onProgress: (Long) -> Unit
    ) {
        val nameBytes = fileName.take(200).toByteArray(Charsets.UTF_8)
        out.writeByte(TYPE_FILE)
        out.writeInt((2 + nameBytes.size + size).toInt())
        out.writeShort(nameBytes.size)
        out.write(nameBytes)
        val buffer = ByteArray(CHUNK)
        var sent = 0L
        while (sent < size) {
            val n = input.read(buffer, 0, minOf(CHUNK.toLong(), size - sent).toInt())
            if (n < 0) throw IOException("הקובץ קצר מהצפוי")
            out.write(buffer, 0, n)
            sent += n
            onProgress(sent)
        }
        out.flush()
    }

    fun skipFully(input: DataInputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skipBytes(minOf(left, Int.MAX_VALUE.toLong()).toInt())
            if (skipped <= 0) {
                input.readByte() // throws EOFException at end of stream
                left--
            } else {
                left -= skipped
            }
        }
    }
}
