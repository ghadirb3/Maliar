package com.persianai.assistant.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.persianai.assistant.models.Contact
import com.persianai.assistant.repository.ContactsRepository
import com.persianai.assistant.utils.STTHelper
import com.persianai.assistant.utils.TTSHelper
import kotlinx.coroutines.delay

class CallContactSelectionDialogue(
    private val context: Context,
    private val sttHelper: STTHelper,
    private val ttsHelper: TTSHelper,
    private val contactsRepository: ContactsRepository
) {
    private val TAG = "CallContactSelection"

    suspend fun startContactSelection(contactName: String): Boolean {
        val contacts = contactsRepository.searchContacts(contactName)

        return when {
            contacts.isEmpty() -> {
                ttsHelper.speakOnlineFirstAndWait("متاسفانه مخاطبی با نام $contactName پیدا نکردم.")
                false
            }
            contacts.size == 1 -> {
                makeCall(contacts[0])
                true
            }
            else -> {
                handleMultipleContacts(contacts, contactName)
            }
        }
    }

    private suspend fun handleMultipleContacts(contacts: List<Contact>, searchName: String): Boolean {
        val message = buildContactListMessage(contacts, searchName)
        ttsHelper.speakOnlineFirstAndWait(message)

        val selection = getUserSelection(contacts.size)
        return if (selection != null && selection in 1..contacts.size) {
            makeCall(contacts[selection - 1])
            true
        } else {
            ttsHelper.speakOnlineFirstAndWait("انتخاب نامعتبر بود. عملیات تماس لغو شد.")
            false
        }
    }

    private fun buildContactListMessage(contacts: List<Contact>, searchName: String): String {
        val sb = StringBuilder("چند مخاطب برای $searchName پیدا شد. کدام یک را شماره گیری کنم؟ ")
        contacts.forEachIndexed { index, contact ->
            sb.append("شماره ${index + 1}: ${contact.name}. ")
        }
        return sb.toString()
    }

    private suspend fun getUserSelection(maxNumber: Int): Int? {
        val result = sttHelper.listenAndWait() ?: return null
        // تبدیل متن عدد به Int (ساده شده)
        return try {
            result.filter { it.isDigit() }.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun makeCall(contact: Contact) {
        val phoneNumber = contact.phoneNumbers.firstOrNull()?.number ?: return
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            context.startActivity(intent)
        } else {
            Log.e(TAG, "Permission CALL_PHONE not granted")
        }
    }
}
