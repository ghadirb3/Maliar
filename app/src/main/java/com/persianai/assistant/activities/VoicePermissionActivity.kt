package com.persianai.assistant.activities

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.persianai.assistant.receivers.VoiceCommandReceiver

class VoicePermissionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // درخواست مجوز میکروفن
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "✅ مجوز میکروفون داده شد", Toast.LENGTH_SHORT).show()

            // بعد از گرفتن مجوز، همان فرمان را دوباره اجرا کن
            val mode = intent.getStringExtra("extra_mode") ?: ""
            val transcript = intent.getStringExtra("extra_transcript") ?: ""
            val notificationId = intent.getIntExtra("extra_notification_id", 0)

            if (transcript.isNotBlank()) {
                val runIntent = Intent(this, VoiceCommandReceiver::class.java).apply {
                    action = VoiceCommandReceiver.ACTION_RUN_COMMAND
                    putExtra(VoiceCommandReceiver.EXTRA_TRANSCRIPT, transcript)
                    putExtra(VoiceCommandReceiver.EXTRA_MODE, mode)
                    putExtra(VoiceCommandReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                }
                sendBroadcast(runIntent)
            }
        } else {
            Toast.makeText(
                this,
                "❌ برای ضبط صدا به مجوز میکروفون نیاز است",
                Toast.LENGTH_LONG
            ).show()
        }
        finish()
    }
}