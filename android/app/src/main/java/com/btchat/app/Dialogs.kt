package com.btchat.app

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object Dialogs {
    fun rename(activity: Activity, chat: ChatController, address: String, onDone: () -> Unit = {}) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(chat.prefs.nickname(address) ?: "")
            hint = chat.systemName(address) ?: address
            setSingleLine()
        }
        val box = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("שם למכשיר")
            .setMessage("השם יוצג רק אצלך. השאר ריק כדי לחזור לשם המקורי.")
            .setView(box)
            .setPositiveButton("שמור") { _, _ ->
                chat.rename(address, input.text.toString())
                onDone()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    fun confirmClearHistory(activity: Activity, chat: ChatController, address: String, onDone: () -> Unit = {}) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("מחיקת היסטוריה")
            .setMessage("למחוק את כל ההודעות עם ${chat.displayName(address)}?\nקבצים שכבר נשמרו לא יימחקו.")
            .setPositiveButton("מחק") { _, _ ->
                chat.clearHistory(address)
                onDone()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }
}
