package com.example.maliar.features.call

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.maliar.R
import com.example.maliar.core.tts.TTSHelper
import com.example.maliar.core.voice.UnifiedVoiceEngine
import com.example.maliar.data.local.ContactEntity
import kotlinx.coroutines.*

class CallContactSelectionDialogue(
    private val context: Context,
    private val ttsHelper: TTSHelper,
    private val voiceEngine: UnifiedVoiceEngine,
    private val contacts: List<ContactEntity>,
    private val onContactSelected: (ContactEntity) -> Unit,
    private val onCancelled: () -> Unit
) {
    companion object {
        private const val TAG = "CallContactSelection"
        private const val CHANNEL_ID = "call_selection_channel"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CALL_PERMISSION = 1002
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isListening = false
    private var currentJob: Job? = null
    private var permissionMonitoringJob: Job? = null
    private var selectedContact: ContactEntity? = null

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "انتخاب مخاطب تماس"
            val descriptionText = "نمایش لیست مخاطبین برای تماس"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun startSelectionDialogue() {
        scope.launch {
            try {
                Log.d(TAG, "Starting contact selection dialogue with ${contacts.size} contacts")
                showNotification()
                speakContactsList()
                listenForUserResponse()
            } catch (e: Exception) {
                Log.e(TAG, "Error in selection dialogue", e)
                cleanup()
                onCancelled()
            }
        }
    }

    private suspend fun speakContactsList() {
        val message = buildString {
            append("لطفاً شماره مخاطب مورد نظر خود را بگویید:\n")
            contacts.forEachIndexed { index, contact ->
                append("${convertToPersianNumber(index + 1)}. ${contact.name}\n")
            }
            append("یا بگویید لغو برای انصراف")
        }
        
        Log.d(TAG, "Speaking contacts list")
        ttsHelper.speakOnlineFirstAndWait(message)
        // حذف delay(2000) - مشکل همپوشانی TTS و ضبط صدا
    }

    private fun convertToPersianNumber(number: Int): String {
        val persianDigits = arrayOf("۰", "۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹")
        return number.toString().map { persianDigits[it.toString().toInt()] }.joinToString("")
    }

    private fun extractNumberFromText(text: String): Int? {
        // تبدیل اعداد فارسی به انگلیسی
        val persianToEnglish = mapOf(
            '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
            '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9'
        )
        
        val normalizedText = text.map { persianToEnglish[it] ?: it }.joinToString("")
        
        // جستجوی عدد در متن
        val numberRegex = Regex("\\d+")
        val match = numberRegex.find(normalizedText)
        
        return match?.value?.toIntOrNull()
    }

    private suspend fun listenForUserResponse() {
        isListening = true
        currentJob = scope.launch {
            try {
                Log.d(TAG, "Starting to listen for user response")
                
                while (isListening && isActive) {
                    val result = voiceEngine.recognizeSpeech()
                    
                    if (result.isSuccess) {
                        val text = result.getOrNull()
                        Log.d(TAG, "Recognized text: $text")
                        
                        if (!text.isNullOrBlank()) {
                            processUserResponse(text)
                            break
                        }
                    } else {
                        Log.e(TAG, "Speech recognition failed", result.exceptionOrNull())
                    }
                    
                    delay(100)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during listening", e)
            } finally {
                isListening = false
            }
        }
    }

    private suspend fun processUserResponse(text: String) {
        Log.d(TAG, "Processing user response: $text")
        
        when {
            text.contains("لغو", ignoreCase = true) || 
            text.contains("انصراف", ignoreCase = true) -> {
                ttsHelper.speakOnlineFirstAndWait("عملیات لغو شد")
                cleanup()
                onCancelled()
            }
            else -> {
                val number = extractNumberFromText(text)
                if (number != null && number > 0 && number <= contacts.size) {
                    val contact = contacts[number - 1]
                    selectedContact = contact
                    startFinalConfirmation(contact)
                } else {
                    ttsHelper.speakOnlineFirstAndWait("شماره نامعتبر است. لطفاً دوباره تلاش کنید")
                    listenForUserResponse()
                }
            }
        }
    }

    private suspend fun startFinalConfirmation(contact: ContactEntity) {
        val message = "آیا می‌خواهید با ${contact.name} تماس بگیرید؟ بگویید بله یا خیر"
        Log.d(TAG, "Starting final confirmation for ${contact.name}")
        
        ttsHelper.speakOnlineFirstAndWait(message)
        // حذف delay(2000) - مشکل همپوشانی TTS و ضبط صدا
        
        listenForFinalConfirmation()
    }

    private suspend fun listenForFinalConfirmation() {
        isListening = true
        currentJob = scope.launch {
            try {
                Log.d(TAG, "Listening for final confirmation")
                
                while (isListening && isActive) {
                    val result = voiceEngine.recognizeSpeech()
                    
                    if (result.isSuccess) {
                        val text = result.getOrNull()
                        Log.d(TAG, "Final confirmation text: $text")
                        
                        if (!text.isNullOrBlank()) {
                            processFinalConfirmationResponse(text)
                            break
                        }
                    }
                    
                    delay(100)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during final confirmation", e)
            } finally {
                isListening = false
            }
        }
    }

    private suspend fun processFinalConfirmationResponse(text: String) {
        Log.d(TAG, "Processing final confirmation: $text")
        
        when {
            text.contains("بله", ignoreCase = true) || 
            text.contains("آره", ignoreCase = true) ||
            text.contains("yes", ignoreCase = true) -> {
                selectedContact?.let { contact ->
                    ttsHelper.speakOnlineFirstAndWait("در حال برقراری تماس با ${contact.name}")
                    // حذف delay(1000) - مشکل همپوشانی
                    makePhoneCall(contact)
                    cleanup()
                    onContactSelected(contact)
                }
            }
            text.contains("خیر", ignoreCase = true) || 
            text.contains("نه", ignoreCase = true) ||
            text.contains("no", ignoreCase = true) -> {
                ttsHelper.speakOnlineFirstAndWait("عملیات لغو شد")
                cleanup()
                onCancelled()
            }
            else -> {
                ttsHelper.speakOnlineFirstAndWait("لطفاً بله یا خیر بگویید")
                listenForFinalConfirmation()
            }
        }
    }

    private fun hasCallPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCallPermission() {
        if (context is Activity) {
            ActivityCompat.requestPermissions(
                context,
                arrayOf(Manifest.permission.CALL_PHONE),
                REQUEST_CALL_PERMISSION
            )
            startPermissionMonitoring()
        } else {
            Log.e(TAG, "Context is not an Activity, cannot request permission")
        }
    }

    private fun startPermissionMonitoring() {
        permissionMonitoringJob?.cancel()
        permissionMonitoringJob = scope.launch {
            var attempts = 0
            while (attempts < 30 && isActive) {
                delay(1000)
                if (hasCallPermission()) {
                    selectedContact?.let { contact ->
                        makePhoneCall(contact)
                    }
                    break
                }
                attempts++
            }
            
            if (attempts >= 30) {
                Log.w(TAG, "Permission monitoring timeout")
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirstAndWait("مجوز تماس داده نشد")
                }
            }
        }
    }

    private fun makePhoneCall(contact: ContactEntity) {
        Log.d(TAG, "Attempting to make phone call to ${contact.name}")
        
        val phoneNumber = contact.phoneNumber
        if (phoneNumber.isBlank()) {
            Log.e(TAG, "Phone number is blank")
            return
        }

        // منطق جدید: همیشه شماره‌گیر باز می‌شود
        try {
            // اگر مجوز داریم، ابتدا ACTION_CALL را امتحان می‌کنیم
            if (hasCallPermission()) {
                try {
                    val callIntent = Intent(Intent.ACTION_CALL).apply {
                        data = Uri.parse("tel:$phoneNumber")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(callIntent)
                    Log.d(TAG, "Phone call initiated with ACTION_CALL")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "ACTION_CALL failed, falling back to ACTION_DIAL: ${e.message}")
                    // ادامه به fallback
                }
            }
            
            // fallback: همیشه ACTION_DIAL را اجرا می‌کنیم (نیازی به مجوز ندارد)
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            Log.d(TAG, "Dialer opened with ACTION_DIAL")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open dialer", e)
            scope.launch {
                ttsHelper.speakOnlineFirstAndWait("خطا در باز کردن شماره‌گیر")
            }
        }
    }

    private fun showNotification() {
        val cancelIntent = Intent(context, CallContactSelectionDialogue::class.java).apply {
            action = "CANCEL_SELECTION"
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("انتخاب مخاطب")
            .setContentText("در حال گوش دادن به انتخاب شما...")
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(R.drawable.ic_cancel, "لغو", cancelPendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun hideNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up dialogue")
        isListening = false
        currentJob?.cancel()
        permissionMonitoringJob?.cancel()
        scope.cancel()
        hideNotification()
    }
}
