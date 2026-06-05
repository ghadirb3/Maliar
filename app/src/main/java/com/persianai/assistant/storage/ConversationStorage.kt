package com.persianai.assistant.storage

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.persianai.assistant.models.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * مدیریت ذخیره‌سازی چت‌ها (مکالمات)
 */
class ConversationStorage(private val context: Context) {
    
    companion object {
        private const val TAG = "ConversationStorage"
    }

    private val prefs = context.getSharedPreferences("conversations", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    /**
     * دریافت همه چت‌ها
     */
    suspend fun getAllConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        try {
            val json = prefs.getString("conversations_list", "[]") ?: "[]"
            val type = object : TypeToken<List<Conversation>>() {}.type
            gson.fromJson<List<Conversation>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversations", e)
            emptyList()
        }
    }
    
    /**
     * ذخیره یک چت
     */
    suspend fun saveConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        try {
            val conversations = getAllConversations().toMutableList()
            
            // حذف چت قدیمی با همین ID (اگر وجود داشت)
            conversations.removeIf { it.id == conversation.id }
            
            // اضافه کردن چت جدید
            conversation.updatedAt = System.currentTimeMillis()
            conversations.add(0, conversation)
            
            // ذخیره
            val json = gson.toJson(conversations)
            prefs.edit().putString("conversations_list", json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save conversation id=${conversation.id}", e)
        }
    }
    
    /**
     * دریافت یک چت بر اساس ID
     */
    suspend fun getConversation(id: String): Conversation? = withContext(Dispatchers.IO) {
        getAllConversations().firstOrNull { it.id == id }
    }

    /**
     * دریافت چت فعال (بر اساس current_conversation_id)
     */
    suspend fun getActiveConversation(): Conversation? = withContext(Dispatchers.IO) {
        try {
            val id = getCurrentConversationId() ?: return@withContext null
            getConversation(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active conversation", e)
            null
        }
    }
    
    /**
     * حذف یک چت
     */
    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        try {
            val conversations = getAllConversations().toMutableList()
            conversations.removeIf { it.id == id }
            
            val json = gson.toJson(conversations)
            prefs.edit().putString("conversations_list", json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete conversation id=$id", e)
        }
    }
    
    /**
     * تغییر عنوان چت
     */
    suspend fun updateConversationTitle(id: String, newTitle: String) = withContext(Dispatchers.IO) {
        try {
            val conversations = getAllConversations().toMutableList()
            val conversation = conversations.firstOrNull { it.id == id }
            
            if (conversation != null) {
                conversation.title = newTitle
                conversation.updatedAt = System.currentTimeMillis()
                
                val json = gson.toJson(conversations)
                prefs.edit().putString("conversations_list", json).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update conversation title id=$id", e)
        }
    }
    
    /**
     * دریافت ID آخرین چت
     */
    fun getCurrentConversationId(): String? {
        return prefs.getString("current_conversation_id", null)
    }

    fun getCurrentConversationId(scope: String): String? {
        return prefs.getString("current_conversation_id_$scope", null)
    }
    
    /**
     * تنظیم ID چت فعلی
     */
    fun setCurrentConversationId(id: String) {
        prefs.edit().putString("current_conversation_id", id).apply()
    }

    fun setCurrentConversationId(scope: String, id: String) {
        prefs.edit().putString("current_conversation_id_$scope", id).apply()
    }
    
    /**
     * حذف ID چت فعلی (برای شروع چت جدید)
     */
    fun clearCurrentConversationId() {
        prefs.edit().remove("current_conversation_id").apply()
    }

    fun clearCurrentConversationId(scope: String) {
        prefs.edit().remove("current_conversation_id_$scope").apply()
    }
}
