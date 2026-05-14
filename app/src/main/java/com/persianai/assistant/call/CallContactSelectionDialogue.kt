package com.persianai.assistant.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.persianai.assistant.core.tts.TTSHelper
import com.persianai.assistant.core.voice.UnifiedVoiceEngine
import com.persianai.assistant.data.Contact
import com.persianai.assistant.data.ContactsRepository
import kotlinx.coroutines.delay

class CallContactSelectionDialogue(
    private val context: Context,
    private val voiceEngine: UnifiedVoiceEngine,
    private val ttsHelper: TTSHelper,
    private val contactsRepository: ContactsRepository
) {
    private val TAG = "CallSelection"

    suspend fun startContactSelection(contactName: String): Boolean {
        Log.d(TAG, "شروع انتخاب مخاطب برای: $contactName")
        val contacts = contactsRepository.searchContacts(contactName)

        return when {
            contacts.isEmpty() -> {
                ttsHelper.speakOnlineFirstAndWait("متأسفانه مخاطبی با نام $contactName پیدا نکردم.")
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
        
        // در اینجا منطق دریافت شماره ردیف از کاربر (STT) باید پیاده شود
        return false 
    }

    private fun buildContactListMessage(contacts: List<Contact>, searchName: String): String {
        val sb = StringBuilder("چند مخاطب با نام $searchName پیدا شد. کدام یک را شماره گیری کنم؟\n")
        contacts.take(5).forEachIndexed { index, contact ->
            sb.append("${index + 1}: ${contact.name}\n")
        }
        return sb.toString()
    }

    private fun makeCall(contact: Contact) {
        val number = contact.phoneNumbers.firstOrNull()?.number ?: return
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            context.startActivity(intent)
        }
    }
}
