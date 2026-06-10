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
import com.persianai.assistant.services.UnifiedVoiceEngine
import com.persianai.assistant.stt.OnlineSTTService
import com.persianai.assistant.services.RecordingResult
import kotlinx.coroutines.delay

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
        // Speak the options and then listen for a short numeric reply
        ttsHelper.speakOnlineFirstAndWait(message)

        val engine = UnifiedVoiceEngine(context)
        if (!engine.hasRequiredPermissions()) {
            ttsHelper.speakOnlineFirstAndWait("برای شنیدن شماره، مجوز میکروفون لازم است")
            return false
        }

        // Small pause to avoid TTS echo being recorded
        delay(400)

        // Start recording with simple VAD (reuse thresholds from VoiceCommandService)
        val start = engine.startRecording()
        if (start.isFailure) {
            ttsHelper.speakOnlineFirstAndWait("خطا در دسترسی به میکروفون")
            return false
        }

        val maxTotalMs = 10_000L
        val maxWaitForSpeechMs = 3_000L
        val silenceStopMs = 1_500L
        val threshold = 800

        var hasSpeech = false
        var lastSpeechTime = 0L
        val startTime = System.currentTimeMillis()

        // Wait for speech with simple VAD
        while (engine.isRecordingInProgress()) {
            val now = System.currentTimeMillis()
            val amp = try { engine.getCurrentAmplitude() } catch (_: Exception) { 0 }

            if (amp > threshold) {
                hasSpeech = true
                lastSpeechTime = now
            }

            val total = now - startTime

            if (!hasSpeech && total > maxWaitForSpeechMs) {
                break
            }

            if (hasSpeech && (now - lastSpeechTime) > silenceStopMs) {
                break
            }

            if (total > maxTotalMs) {
                break
            }

            delay(100)
        }

        val stopResult = engine.stopRecording()
        val recording = stopResult.getOrNull()
        if (recording == null) {
            ttsHelper.speakOnlineFirstAndWait("صدایی دریافت نشد، لطفاً دوباره تلاش کنید")
            return false
        }

        // Transcribe with online STT service
        val sttService = OnlineSTTService(context)
        val sttResult = sttService.transcribeAudio(recording.file)

        try { recording.file.delete() } catch (_: Exception) {}

        if (!sttResult.isSuccess || sttResult.text.isBlank()) {
            ttsHelper.speakOnlineFirstAndWait("متاسفانه متن شماره شنیده نشد، لطفاً دوباره تلاش کنید")
            return false
        }

        val number = parseNumberFromText(sttResult.text)
        if (number != null && number in 1..contacts.size) {
            val contact = contacts[number - 1]
            ttsHelper.speakOnlineFirstAndWait("در حال تماس با ${contact.name}")
            makeCall(contact)
            return true
        }

        ttsHelper.speakOnlineFirstAndWait("انتخاب نامعتبر شد، لطفاً دوباره تلاش کنید")
        return false
    }

    private fun parseNumberFromText(text: String): Int? {
        // Normalize digits first (Persian/Arabic to Latin)
        val normalized = TTSHelper.normalizeDigits(text)

        // Try to find explicit digits
        val digitRegex = Regex("\\d+")
        val found = digitRegex.find(normalized)
        if (found != null) return found.value.toIntOrNull()

        // Map common Persian words to numbers
        val map = mapOf(
            "یک" to 1, "اول" to 1, "نخست" to 1,
            "دو" to 2, "دوم" to 2,
            "سه" to 3, "سوم" to 3,
            "چهار" to 4, "چهارم" to 4,
            "پنج" to 5, "شش" to 6, "هفت" to 7,
            "هشت" to 8, "نه" to 9, "ده" to 10
        )

        val lower = normalized.lowercase()
        for ((k, v) in map) {
            if (lower.contains(k)) return v
        }

        return null
    }

    private fun buildContactListMessage(contacts: List<Contact>, searchName: String): String {
        val options = contacts.mapIndexed { index, contact ->
            "شماره ${index + 1}: ${contact.name} (${contact.phoneNumber})"
        }.joinToString(". ")
        return "چند مخاطب برای $searchName پیدا شد: $options. لطفاً شماره مورد نظر را بگویید."
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
