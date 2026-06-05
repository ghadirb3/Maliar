package com.persianai.assistant.utils

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

/**
 * کلیدهای پیش‌فرض API به صورت رمزگذاری شده
 */
object DefaultApiKeys {
    
    private val ENCRYPTION_KEY: String
        get() = System.getProperty("default.api.enc.key", "")
    private val ENCRYPTION_IV: String
        get() = System.getProperty("default.api.enc.iv", "")
    
    // ⚠️ توجه: این فایل فقط برای توسعه‌دهندگان است
    // کاربران عادی باید از تنظیمات برنامه (API Settings) کلیدهای خود را وارد کنند
    // اگر می‌خواهید کلید پیش‌فرض برای تست قرار دهید:
    // 1. به https://platform.openai.com/api-keys بروید و کلید بگیرید
    // 2. کلید را در خط زیر جایگزین کنید
    // 3. این فایل را هرگز در گیت commit نکنید!
    
    private val ENCRYPTED_KEYS = mapOf(
        "openai_1" to encryptKey(""),       // کلید OpenAI - خالی است، از تنظیمات برنامه استفاده کنید
        "aimlapi_1" to encryptKey(""),      // کلید AIML - خالی است
        "openrouter_1" to encryptKey(""),   // کلید OpenRouter - خالی است
        "huggingface_1" to encryptKey("")   // کلید HuggingFace - خالی است
    )
    
    /**
     * دریافت کلید OpenAI برای Whisper
     */
    fun getOpenAIKey(): String? {
        return try {
            decryptKey(ENCRYPTED_KEYS["openai_1"] ?: return null)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * دریافت کلید AIML API
     */
    fun getAIMLKey(): String? {
        return try {
            decryptKey(ENCRYPTED_KEYS["aimlapi_1"] ?: return null)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * دریافت کلید OpenRouter
     */
    fun getOpenRouterKey(): String? {
        return try {
            decryptKey(ENCRYPTED_KEYS["openrouter_1"] ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * دریافت کلید HuggingFace (برای STT)
     */
    fun getHuggingFaceKey(): String? {
        return try {
            decryptKey(ENCRYPTED_KEYS["huggingface_1"] ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * لینک مدل Vosk فارسی برای دانلود زمان اجرا
     */
    fun getVoskFaModelUrl(): String =
        "https://github.com/rhasspy/fa_kaldi-rhasspy/releases/download/v1.0/vosk-model-small-fa-rhasspy-0.15.zip"
    
    /**
     * رمزگذاری کلید
     */
    private fun encryptKey(key: String): String {
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(ENCRYPTION_KEY.toByteArray(), "AES")
            val ivSpec = IvParameterSpec(ENCRYPTION_IV.toByteArray())
            
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            val encrypted = cipher.doFinal(key.toByteArray())
            
            return Base64.encodeToString(encrypted, Base64.DEFAULT)
        } catch (e: Exception) {
            return key
        }
    }
    
    /**
     * رمزگشایی کلید
     */
    private fun decryptKey(encryptedKey: String): String {
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(ENCRYPTION_KEY.toByteArray(), "AES")
            val ivSpec = IvParameterSpec(ENCRYPTION_IV.toByteArray())
            
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decrypted = cipher.doFinal(Base64.decode(encryptedKey, Base64.DEFAULT))
            
            return String(decrypted)
        } catch (e: Exception) {
            return ""
        }
    }
    
    /**
     * بررسی و استفاده از کلید پیش‌فرض اگر کاربر کلید ندارد
     */
    fun initializeDefaultKeys(context: android.content.Context) {
        val prefs = context.getSharedPreferences("api_keys", android.content.Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        // اگر کلید OpenAI ندارد، کلید پیش‌فرض را قرار بده
        if (prefs.getString("openai_api_key", null).isNullOrEmpty()) {
            getOpenAIKey()?.let {
                editor.putString("openai_api_key", it)
                editor.putBoolean("openai_is_default", true)
            }
        }
        
        // اگر کلید AIML ندارد
        if (prefs.getString("aiml_api_key", null).isNullOrEmpty()) {
            getAIMLKey()?.let {
                editor.putString("aiml_api_key", it)
                editor.putBoolean("aiml_is_default", true)
            }
        }
        
        editor.apply()
    }
}
