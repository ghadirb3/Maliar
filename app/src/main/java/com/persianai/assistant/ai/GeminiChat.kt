package com.persianai.assistant.ai

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * چت واقعی با Gemini AI
 */
class GeminiChat {
    
    companion object {
        private const val TAG = "GeminiChat"
        private val API_KEY = com.persianai.assistant.BuildConfig.GEMINI_API_KEY
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent"
    }
    
    fun sendMessage(
        message: String,
        context: String = "",
        callback: (String?) -> Unit
    ) {
        Thread {
            try {
                val url = "$BASE_URL?key=$API_KEY"
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                
                // ساخت درخواست
                val prompt = if (context.isNotEmpty()) {
                    "$context\n\nسوال: $message"
                } else {
                    message
                }
                
                val requestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                }
                
                connection.outputStream.write(requestBody.toString().toByteArray())
                
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    
                    val candidates = json.getJSONArray("candidates")
                    if (candidates.length() > 0) {
                        val content = candidates.getJSONObject(0)
                            .getJSONObject("content")
                        val parts = content.getJSONArray("parts")
                        if (parts.length() > 0) {
                            val text = parts.getJSONObject(0).getString("text")
                            Log.d(TAG, "✅ Response: $text")
                            callback(text)
                        } else {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                } else {
                    Log.e(TAG, "❌ Error: ${connection.responseCode}")
                    callback(null)
                }
                
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception", e)
                callback(null)
            }
        }.start()
    }
    
    /**
     * چت مخصوص حسابداری
     */
    fun askFinanceQuestion(question: String, transactions: String, callback: (String?) -> Unit) {
        val context = """
        شما یک دستیار مالی هستید. اطلاعات تراکنش‌های مالی کاربر:
        $transactions
        
        لطفاً به زبان فارسی پاسخ دهید و تحلیل مالی ارائه دهید.
        """.trimIndent()
        
        sendMessage(question, context, callback)
    }
    
    /**
     * چت مخصوص مسیریابی
     */
    fun askNavigationQuestion(question: String, locationHistory: String, callback: (String?) -> Unit) {
        val context = """
        شما یک دستیار مسیریابی هستید. تاریخچه مکان‌های کاربر:
        $locationHistory
        
        لطفاً به زبان فارسی پاسخ دهید و پیشنهاد مسیر ارائه دهید.
        """.trimIndent()
        
        sendMessage(question, context, callback)
    }
    
    /**
     * چت مخصوص یادآور
     */
    fun askReminderQuestion(question: String, reminders: String, callback: (String?) -> Unit) {
        val context = """
        شما یک دستیار یادآوری هستید. یادآورهای کاربر:
        $reminders
        
        لطفاً به زبان فارسی پاسخ دهید و در مدیریت زمان کمک کنید.
        """.trimIndent()
        
        sendMessage(question, context, callback)
    }
}
