package com.persianai.assistant.activities

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.persianai.assistant.R
import com.persianai.assistant.services.RecordingResult
import com.persianai.assistant.services.UnifiedVoiceEngine
import com.persianai.assistant.utils.SystemIntegrationHelper
import com.persianai.assistant.call.CallIntentProcessor
import com.persianai.assistant.call.CallConfirmationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class VoiceCallActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var recordButton: Button
    private lateinit var cancelButton: Button

    private data class ContactPhone(
        val contactId: String,
        val displayName: String,
        val phoneNumber: String
    )

    private data class ContactMatch(
        val displayName: String,
        val score: Int,
        val numbers: List<String>
    )

    private val permReqCode = 9011
    private val requiredPerms = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_call)

        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        recordButton = findViewById(R.id.recordButton)
        cancelButton = findViewById(R.id.cancelButton)

        cancelButton.setOnClickListener { finish() }
        recordButton.setOnClickListener {
            if (!ensurePermissions()) return@setOnClickListener
            startVoiceCallFlow()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != permReqCode) return

        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            startVoiceCallFlow()
        } else {
            statusText.text = "❌ برای تماس صوتی، مجوز میکروفن و مخاطبین لازم است"
        }
    }

    private fun ensurePermissions(): Boolean {
        val missing = requiredPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true

        ActivityCompat.requestPermissions(this, missing.toTypedArray(), permReqCode)
        return false
    }

    private fun startVoiceCallFlow() {
        recordButton.isEnabled = false
        cancelButton.isEnabled = false
        statusText.text = "🎤 ضبط فرمان تماس..."
        transcriptText.text = ""

        lifecycleScope.launch {
            val engine = UnifiedVoiceEngine(this@VoiceCallActivity)

            if (!engine.hasRequiredPermissions()) {
                statusText.text = "❌ مجوز میکروفن لازم است"
                recordButton.isEnabled = true
                cancelButton.isEnabled = true
                return@launch
            }

            val recording = recordWithVad(engine)
            if (recording == null) {
                statusText.text = "⚠️ چیزی شنیده نشد"
                recordButton.isEnabled = true
                cancelButton.isEnabled = true
                return@launch
            }

            statusText.text = "📝 تبدیل گفتار به متن..."
            val analysis = engine.analyzeHybrid(recording.file)

            try { recording.file.delete() } catch (_: Exception) {}

            val text = analysis.getOrNull()?.primaryText?.trim().orEmpty()
            transcriptText.text = text

            if (text.isBlank()) {
                statusText.text = "⚠️ متن تشخیص داده نشد"
                recordButton.isEnabled = true
                cancelButton.isEnabled = true
                return@launch
            }

            statusText.text = "📇 در حال جستجوی مخاطب..."
            handleTranscriptForCall(text)
        }
    }

    private suspend fun recordWithVad(engine: UnifiedVoiceEngine): RecordingResult? {
        return try {
            val start = engine.startRecording()
            if (start.isFailure) return null

            val startTime = System.currentTimeMillis()
            var hasSpeech = false
            var lastSpeechTime = 0L
            val maxTotalMs = 8_000L
            val maxWaitForSpeechMs = 3_500L
            val silenceStopMs = 1_000L
            val threshold = 900

            while (engine.isRecordingInProgress()) {
                val now = System.currentTimeMillis()
                val amp = engine.getCurrentAmplitude()
                if (amp > threshold) {
                    hasSpeech = true
                    lastSpeechTime = now
                }

                val total = now - startTime
                if (!hasSpeech && total > maxWaitForSpeechMs) break
                if (hasSpeech && (now - lastSpeechTime) > silenceStopMs) break
                if (total > maxTotalMs) break

                delay(120)
            }

            val stop = engine.stopRecording()
            stop.getOrNull()
        } catch (_: Exception) {
            try { engine.cancelRecording() } catch (_: Exception) {}
            null
        }
    }

    private fun handleTranscriptForCall(text: String) {
        lifecycleScope.launch {
            try {
                statusText.text = "🔍 در حال پردازش فرمان تماس..."
                
                // استفاده از سیستم جدید پردازش فرمان تماس
                val callProcessor = CallIntentProcessor(this@VoiceCallActivity)
                val result = callProcessor.processCallIntent(text)
                
                when (result) {
                    is CallIntentProcessor.CallIntentResult.SingleContact -> {
                        statusText.text = "✅ مخاطب پیدا شد: ${result.contact.name}"
                        
                        // شروع فرآیند تأیید تماس
                        val confirmationManager = CallConfirmationManager(this@VoiceCallActivity)
                        confirmationManager.startCallConfirmation(result.contact, result.contact.phoneNumber)
                        
                        // بستن صفحه تماس صوتی بعد از شروع تأیید
                        delay(1000)
                        finish()
                    }
                    
                    is CallIntentProcessor.CallIntentResult.MultipleContacts -> {
                        val contactsText = result.contacts.take(3).joinToString("، ") { it.name }
                        statusText.text = "📞 ${result.contacts.size} مخاطب پیدا شد:\n$contactsText"
                        
                        AlertDialog.Builder(this@VoiceCallActivity)
                            .setTitle("چندین مخاطب پیدا شد")
                            .setMessage("${result.contacts.size} مخاطب مشابه پیدا شد:\n\n$contactsText\n\nلطفاً نام دقیق‌تر بگویید.")
                            .setPositiveButton("باشه") { _, _ ->
                                recordButton.isEnabled = true
                                cancelButton.isEnabled = true
                            }
                            .show()
                    }
                    
                    is CallIntentProcessor.CallIntentResult.ContactNotFound -> {
                        statusText.text = "❌ مخاطب پیدا نشد"
                        
                        AlertDialog.Builder(this@VoiceCallActivity)
                            .setTitle("مخاطب پیدا نشد")
                            .setMessage(result.message)
                            .setPositiveButton("باشه") { _, _ ->
                                recordButton.isEnabled = true
                                cancelButton.isEnabled = true
                            }
                            .show()
                    }
                    
                    is CallIntentProcessor.CallIntentResult.PermissionRequired -> {
                        statusText.text = "🔒 نیاز به مجوز"
                        
                        AlertDialog.Builder(this@VoiceCallActivity)
                            .setTitle("نیاز به مجوز")
                            .setMessage(result.message)
                            .setPositiveButton("تنظیمات") { _, _ ->
                                // باز کردن تنظیمات برنامه
                                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.fromParts("package", packageName, null)
                                }
                                startActivity(intent)
                            }
                            .setNegativeButton("لغو") { _, _ ->
                                recordButton.isEnabled = true
                                cancelButton.isEnabled = true
                            }
                            .show()
                    }
                    
                    is CallIntentProcessor.CallIntentResult.NotRecognized -> {
                        statusText.text = "❓ فرمان نامشخص"
                        
                        AlertDialog.Builder(this@VoiceCallActivity)
                            .setTitle("فرمان نامشخص")
                            .setMessage(result.message + "\n\nمثال: «تماس با علی» یا «با مریم تماس بگیر»")
                            .setPositiveButton("باشه") { _, _ ->
                                recordButton.isEnabled = true
                                cancelButton.isEnabled = true
                            }
                            .show()
                    }
                    
                    is CallIntentProcessor.CallIntentResult.Cancel -> {
                        statusText.text = "❌ لغو شد"
                        recordButton.isEnabled = true
                        cancelButton.isEnabled = true
                    }
                    
                    is CallIntentProcessor.CallIntentResult.Error -> {
                        statusText.text = "❌ خطا"
                        
                        AlertDialog.Builder(this@VoiceCallActivity)
                            .setTitle("خطا")
                            .setMessage(result.message)
                            .setPositiveButton("باشه") { _, _ ->
                                recordButton.isEnabled = true
                                cancelButton.isEnabled = true
                            }
                            .show()
                    }
                }
                
            } catch (e: Exception) {
                statusText.text = "❌ خطا: ${e.message}"
                recordButton.isEnabled = true
                cancelButton.isEnabled = true
            }
        }
    }

    private fun normalizePhoneNumber(raw: String): String {
        return raw
            .replace("[\\s()-]".toRegex(), "")
            .trim()
    }
}
