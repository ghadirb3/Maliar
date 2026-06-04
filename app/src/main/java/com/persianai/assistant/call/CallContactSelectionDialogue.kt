package com.persianai.assistant.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.persianai.assistant.models.Contact
import com.persianai.assistant.utils.TTSHelper

/**
 * دیالوگ انتخاب مخاطب برای ماژول تماس.
 *
 * این نسخه از همان ContactSearcher موجود پروژه استفاده می‌کند تا وابستگی‌های حذف‌شده
 * ContactsRepository/STTHelper دوباره بیلد را نشکنند.
 */
class CallContactSelectionDialogue(
    private val context: Context,
    private val ttsHelper: TTSHelper,
    private val contactSearcher: ContactSearcher = ContactSearcher(context)
) {
    private val TAG = "CallContactSelection"

    suspend fun startContactSelection(contactName: String): Boolean {
        val contacts = contactSearcher.searchContacts(contactName)

        return when {
            contacts.isEmpty() -> {
                ttsHelper.speakOnlineFirstAndWait("متاسفانه مخاطبی با نام $contactName پیدا نکردم.")
                false
            }
            contacts.size == 1 -> {
                makeCall(contacts[0])
                true
            }
            else -> handleMultipleContacts(contacts, contactName)
        }
    }

    private suspend fun handleMultipleContacts(contacts: List<Contact>, searchName: String): Boolean {
        val message = buildContactListMessage(contacts, searchName)
        ttsHelper.speakOnlineFirstAndWait(message)
        return false
    }

    private fun buildContactListMessage(contacts: List<Contact>, searchName: String): String {
        val options = contacts.mapIndexed { index, contact ->
            "شماره ${index + 1}: ${contact.name}"
        }.joinToString(". ")
        return "چند مخاطب برای $searchName پیدا شد: $options. لطفاً نام را دقیق‌تر بگویید."
    }

    private fun makeCall(contact: Contact) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:${contact.phoneNumber}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            context.startActivity(intent)
        } else {
            Log.e(TAG, "Permission CALL_PHONE not granted")
        }
    }
}
