package com.persianai.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.persianai.assistant.ai.PuterBridge
import com.persianai.assistant.config.APIKeyConfig
import com.persianai.assistant.config.RemoteAIConfigManager
import com.persianai.assistant.services.UnifiedVoiceEngine
import com.persianai.assistant.utils.AutoProvisioningManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AIAssistantApplication : Application() {

    companion object {
        const val CHANNEL_ID = "ai_assistant_channel"
        const val CHANNEL_NAME = "دستیار هوش مصنوعی"
        lateinit var instance: AIAssistantApplication
            private set
    }

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        PuterBridge.setContext(this)
        
        // ✅ Initialize API Keys configuration (hardcoded GapGPT fallback)
        Log.d("AIAssistantApplication", "🔄 Initializing API Key configuration...")
        APIKeyConfig.initializeKeys(this)
        
        // ✅ Auto-provision keys from remote (Liara + GapGPT)
        appScope.launch {
            try {
                Log.d("AIAssistantApplication", "🔄 Auto-provisioning API keys...")
                val result = AutoProvisioningManager.autoProvision(this@AIAssistantApplication)
                if (result.isSuccess) {
                    val keys = result.getOrNull().orEmpty()
                    Log.i("AIAssistantApplication", "✅ AutoProvision: ${keys.size} keys loaded")
                } else {
                    Log.w("AIAssistantApplication", "⚠️ AutoProvision failed: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.w("AIAssistantApplication", "⚠️ AutoProvision exception: ${e.message}")
            }
            
            // بارگذاری remote AI config (مدل‌ها و پیام‌ها)
            try {
                RemoteAIConfigManager.getInstance(this@AIAssistantApplication).refreshAndCache()
                Log.i("AIAssistantApplication", "✅ Remote AI config loaded")
            } catch (e: Exception) {
                Log.w("AIAssistantApplication", "⚠️ Remote AI config failed: ${e.message}")
            }
        }

        // Dev-only: if a host path is provided via env var, copy Haaniye model into app files
        try {
            if (BuildConfig.DEBUG) {
                val hostPath = System.getenv("HAANIYE_HOST_PATH")
                    ?: "${projectDirPlaceholder()}" // placeholder if needed
                if (!hostPath.isNullOrBlank()) {
                    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                    scope.launch {
                        try {
                            UnifiedVoiceEngine(this@AIAssistantApplication).copyHaaniyeFromHost(hostPath)
                        } catch (_: Exception) { /* ignore dev helper failures */ }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // projectDir is not available at runtime; this helper returns an impossible path placeholder
    private fun projectDirPlaceholder(): String = "C:/github/PersianAIAssistantOnline/app/build/intermediates/assets/debug/tts/haaniye"

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "کانال اعلان‌های دستیار هوش مصنوعی"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
