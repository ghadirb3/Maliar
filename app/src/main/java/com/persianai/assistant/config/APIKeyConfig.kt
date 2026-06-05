package com.persianai.assistant.config

import android.content.Context
import android.util.Log
import com.persianai.assistant.models.APIKey
import com.persianai.assistant.models.AIProvider
import com.persianai.assistant.utils.PreferencesManager

/**
 * ✅ API Keys کی تشکیل اور initialization
 * 
 * یہ configuration صرف دستی اضافے کے لیے ہے۔
 * براہ کرم اپنی keys یہاں ڈالیں:
 */
object APIKeyConfig {
    
    private const val TAG = "APIKeyConfig"
    
    /**
     * ✅ پہلے سے موجود keys (براہ کرم update کریں):
     * 
     * مثال:
     * APIKey(
     *     key = "sk-proj-YOUR_ACTUAL_OPENAI_KEY_HERE",
     *     provider = AIProvider.OPENAI,
     *     baseUrl = "https://api.openai.com/v1",
     *     isActive = true
     * )
     */
    fun getInitialAPIKeys(): List<APIKey> {
        return listOf(
            // ✅ GapGPT (اولویت fallback - همیشه فعال)
            APIKey(
                provider = AIProvider.GAPGPT,
                key = "sk-Gz3ACu1VPhi7eHKxblbQFAmGGC706T16J4ZtVqoGwyon2ONJ",
                baseUrl = "https://api.gapgpt.app/v1",
                isActive = true
            ),
        )
    }
    
    /**
     * ✅ Initialization - یہ app شروع میں چل۔
     */
    fun initializeKeys(context: Context) {
        try {
            Log.d(TAG, "🔄 Initializing API Keys...")
            
            val prefs = PreferencesManager(context)
            val existingKeys = prefs.getAPIKeys().toMutableList()
            
            if (existingKeys.isEmpty()) {
                Log.d(TAG, "📝 No existing keys found, setting up defaults...")
                val initialKeys = getInitialAPIKeys()
                    .filter { !it.key.isNullOrBlank() }
                
                if (initialKeys.isEmpty()) {
                    Log.w(TAG, "⚠️ No valid keys to initialize - user must add keys manually")
                    return
                }
                
                prefs.saveAPIKeys(initialKeys)
                Log.d(TAG, "✅ Initial keys saved: ${initialKeys.size}")
                
                initialKeys.forEach { key ->
                    Log.d(TAG, "   - ${key.provider.name}: ${if (key.isActive) "✔ ACTIVE" else "✕"}")
                }
            } else {
                // اطمینان از وجود GapGPT key به عنوان fallback
                val hasGapgpt = existingKeys.any { 
                    it.provider == AIProvider.GAPGPT && it.isActive && it.key.isNotBlank() 
                }
                if (!hasGapgpt) {
                    val fallbackKey = getInitialAPIKeys().find { it.provider == AIProvider.GAPGPT }
                    if (fallbackKey != null) {
                        existingKeys.add(fallbackKey)
                        prefs.saveAPIKeys(existingKeys)
                        Log.d(TAG, "✅ GapGPT fallback key added to existing keys")
                    }
                }
                
                Log.d(TAG, "✅ Existing keys found: ${existingKeys.size}")
                existingKeys.forEach { key ->
                    Log.d(TAG, "   - ${key.provider.name}: ${if (key.isActive) "✔ ACTIVE" else "✕"} (${key.key.take(8)}...)")
                }
            }
            
            // Validate keys
            validateKeys(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing keys", e)
        }
    }
    
    /**
     * ✅ Keys کی تصدیق - کون سی keys working ہیں
     */
    private fun validateKeys(context: Context) {
        try {
            val prefs = PreferencesManager(context)
            val keys = prefs.getAPIKeys()
            
            Log.d(TAG, "📊 API Key Validation:")
            
            val activeProviders = keys.filter { it.isActive }
                .map { it.provider.name }
                .distinct()
            
            Log.d(TAG, "   Active providers: ${activeProviders.joinToString(", ")}")
            
            val openaiKey = keys.find { it.provider == AIProvider.OPENAI && it.isActive }
            if (openaiKey != null) {
                Log.d(TAG, "   ✅ OpenAI: Available")
            } else {
                Log.w(TAG, "   ⚠️ OpenAI: NOT SET (app may not work)")
            }
            
            // Warning اگر کوئی بھی key نہیں
            if (keys.isEmpty()) {
                Log.e(TAG, "   ❌ ERROR: No API keys configured!")
                Log.e(TAG, "   💡 Please add at least one API key in Settings or APIKeyConfig.kt")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error validating keys", e)
        }
    }
    
    /**
     * ✅ Manual key addition (اگر user dashboard میں add نہ کر سکے)
     */
    fun addManualKey(context: Context, key: APIKey): Boolean {
        return try {
            if (key.key.isNullOrBlank()) {
                Log.w(TAG, "Cannot add key: empty key value")
                return false
            }
            
            val prefs = PreferencesManager(context)
            val existingKeys = prefs.getAPIKeys().toMutableList()
            
            // Remove duplicate provider if exists
            existingKeys.removeAll { it.provider == key.provider }
            
            // Add new key
            existingKeys.add(key)
            
            prefs.saveAPIKeys(existingKeys)
            Log.d(TAG, "✅ Manual key added: ${key.provider.name}")
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding manual key", e)
            false
        }
    }
    
    /**
     * ✅ Priority order for model selection
     */
    fun getPreferredProvider(context: Context): AIProvider? {
        val prefs = PreferencesManager(context)
        val activeKeys = prefs.getAPIKeys().filter { it.isActive }
        
        // ✅ Priority: Liara اول، GapGPT دوم
        return when {
            activeKeys.any { it.provider == AIProvider.LIARA } -> AIProvider.LIARA
            activeKeys.any { it.provider == AIProvider.GAPGPT } -> AIProvider.GAPGPT
            activeKeys.any { it.provider == AIProvider.OPENAI } -> AIProvider.OPENAI
            activeKeys.any { it.provider == AIProvider.AIML } -> AIProvider.AIML
            activeKeys.any { it.provider == AIProvider.OPENROUTER } -> AIProvider.OPENROUTER
            activeKeys.any { it.provider == AIProvider.AVALAI } -> AIProvider.AVALAI
            activeKeys.any { it.provider == AIProvider.ANTHROPIC } -> AIProvider.ANTHROPIC
            activeKeys.any { it.provider == AIProvider.GLADIA } -> AIProvider.GLADIA
            activeKeys.any { it.provider == AIProvider.IVIRA } -> AIProvider.IVIRA
            else -> null
        }
    }
}