package com.btchat.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.btchat.app.databinding.ItemMessageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MessageAdapter(
    private val context: Context,
    private val items: List<ChatMessage>,
    private val onClick: (ChatMessage) -> Unit,
    private val onLongClick: (ChatMessage) -> Unit
) : RecyclerView.Adapter<MessageAdapter.Holder>() {

    class Holder(val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root)

    private val thumbs = LruCache<String, Bitmap>(30)
    private val loader = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRESS)) bindStatus(holder.b, items[position])
        else onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val m = items[position]
        val b = holder.b
        val maxBubble = (context.resources.displayMetrics.widthPixels * 0.78f).toInt()

        val lp = b.bubble.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (m.outgoing) Gravity.END else Gravity.START
        b.bubble.layoutParams = lp
        b.bubble.setBackgroundResource(if (m.outgoing) R.drawable.bubble_out else R.drawable.bubble_in)

        if (m.kind == MsgKind.TEXT) {
            b.txtBody.isVisible = true
            b.txtBody.maxWidth = maxBubble
            b.txtBody.text = m.text
            b.fileBlock.isVisible = false
        } else {
            b.txtBody.isVisible = false
            b.fileBlock.isVisible = true
            b.fileName.maxWidth = maxBubble - dp(64)
            b.fileName.text = m.fileName
            bindThumb(b, m)
        }
        bindStatus(b, m)

        b.bubble.setOnClickListener { onClick(m) }
        b.bubble.setOnLongClickListener { onLongClick(m); true }
        b.txtBody.setOnLongClickListener { onLongClick(m); true }
    }

    private fun bindStatus(b: ItemMessageBinding, m: ChatMessage) {
        val pending = m.status == MsgStatus.PENDING
        if (m.kind == MsgKind.FILE) {
            val size = formatSize(m.fileSize)
            b.fileInfo.text = when {
                pending -> "$size · ${m.progress}%"
                m.status == MsgStatus.FAILED -> "$size · נכשל"
                else -> size
            }
            b.progress.isVisible = pending
            if (pending) b.progress.setProgressCompat(m.progress, true)
        } else {
            b.progress.isVisible = false
        }
        val time = formatTime(m.time)
        b.txtMeta.text = if (!m.outgoing) time else when (m.status) {
            MsgStatus.PENDING -> "$time  ⏳"
            MsgStatus.DONE -> "$time  ✓"
            MsgStatus.FAILED -> "$time  ⚠ לא נשלח"
        }
    }

    private fun bindThumb(b: ItemMessageBinding, m: ChatMessage) {
        val uriString = m.fileUri
        val isImage = uriString != null && FileStore.mimeOf(m.fileName ?: "").startsWith("image/")
        b.thumb.tag = uriString
        if (!isImage || uriString == null) {
            b.thumb.isVisible = false
            return
        }
        val cached = thumbs.get(uriString)
        if (cached != null) {
            b.thumb.setImageBitmap(cached)
            b.thumb.isVisible = true
            return
        }
        b.thumb.isVisible = false
        loader.execute {
            val bmp = try {
                context.contentResolver.loadThumbnail(Uri.parse(uriString), Size(480, 480), null)
            } catch (_: Exception) {
                null
            }
            if (bmp != null) {
                main.post {
                    thumbs.put(uriString, bmp)
                    if (b.thumb.tag == uriString) {
                        b.thumb.setImageBitmap(bmp)
                        b.thumb.isVisible = true
                    }
                }
            }
        }
    }

    private fun formatTime(time: Long): String =
        if (DateUtils.isToday(time)) timeFormat.format(Date(time)) else dateTimeFormat.format(Date(time))

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        const val PAYLOAD_PROGRESS = "progress"

        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
