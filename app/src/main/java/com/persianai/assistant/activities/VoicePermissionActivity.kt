package com.persianai.assistant.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.persianai.assistant.services.VoiceCommandService

/**
 * Activity to request microphone permission for voice commands from notification
 */
class VoicePermissionActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 101
        private const val EXTRA_ACTION = "extra_action"
        private const val EXTRA_MODE = "extra_mode"
        private const val EXTRA_HINT = "extra_hint"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if permission is already granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            == PackageManager.PERMISSION_GRANTED) {
            // Permission already granted, start voice service
            startVoiceService()
            finish()
        } else {
            // Request permission
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            REQUEST_RECORD_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, start voice service
                    Toast.makeText(this, "✅ مجوز میکروفون داده شد", Toast.LENGTH_SHORT).show()
                    startVoiceService()
                } else {
                    // Permission denied
                    Toast.makeText(this, "❌ برای ضبط صدا به مجوز میکروفون نیاز است", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
    }

    private fun startVoiceService() {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: VoiceCommandService.ACTION_RECORD_COMMAND
        val mode = intent.getStringExtra(EXTRA_MODE) ?: VoiceCommandService.MODE_GENERAL
        val hint = intent.getStringExtra(EXTRA_HINT)
        
        val serviceIntent = Intent(this, VoiceCommandService::class.java).apply {
            this.action = action
            putExtra(VoiceCommandService.EXTRA_MODE, mode)
            putExtra(VoiceCommandService.EXTRA_HINT, hint)
        }
        
        startForegroundService(serviceIntent)
    }
}
