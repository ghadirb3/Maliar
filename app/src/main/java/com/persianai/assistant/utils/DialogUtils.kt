package com.persianai.assistant.utils

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object DialogUtils {

    fun showDeleteConfirmation(
        context: Context,
        itemLabel: String,
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle("❌ حذف $itemLabel")
            .setMessage("آیا از حذف این $itemLabel مطمئن هستید؟")
            .setPositiveButton("حذف") { _, _ -> onConfirm() }
            .setNegativeButton("لغو", null)
            .show()
    }

    fun showDeleteConfirmation(
        context: Context,
        title: String,
        message: String,
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("حذف") { _, _ -> onConfirm() }
            .setNegativeButton("لغو", null)
            .show()
    }

    fun showSuccessToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
