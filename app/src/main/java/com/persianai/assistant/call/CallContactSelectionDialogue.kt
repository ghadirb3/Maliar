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

class CallContactSelectionDialogue(
    private val context: Context,
    private val voiceEngine: UnifiedVoiceEngine,
    private val ttsHelper: TTSHelper,
    private val contactsRepository: ContactsRepository
) {
    private val TAG = "CallSelection"

    suspend fun startContactSelection(contactName: String): Boolean {
        Log.d(TAG, "شروع فرآیند تماس برای: $contactName")
        val contacts = contactsRepository.searchContacts(contactName)

        return when {
            contacts.isEmpty() -> {
                ttsHelper.speakOnlineFirstAndWait("مخاطبی با نام $contactName یافت نشد.")
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
        
        // اینجا باید منتظر پاسخ صوتی کاربر برای انتخاب شماره ردیف بمانیم
        // فعلاً برای جلوگیری از توقف برنامه، اولین مورد را در نظر می‌گیریم یا منتظر پیاده‌سازی STT می‌مانیم
        return false 
    }

    private fun buildContactListMessage(contacts: List<Contact>, searchName: String): String {
        val sb = StringBuilder("چند مورد برای $searchName پیدا شد:\n")
        contacts.take(5).forEachIndexed { index, contact ->
            sb.append("${index + 1}: ${contact.name}\n")
        }
        sb.append("کدام شماره را بگیریم؟")
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
        } else {
            Log.e(TAG, "مجوز تماس داده نشده است.")
        }
    }
}
