package com.persianai.assistant.ai

import android.content.Context
import android.content.Intent
import com.persianai.assistant.activities.ImprovedMusicActivity
// import com.persianai.assistant.activities.NavigationActivity

/**
 * دستیار هوشمند برای کنترل برنامه
 */
object SmartAssistant {
    
    fun processCommand(context: Context, command: String): String {
        val cmd = command.trim().lowercase()
        
        return when {
            // موزیک
            cmd.contains("آهنگ") || cmd.contains("موزیک") || cmd.contains("موسیقی") -> {
                handleMusicCommand(context, cmd)
            }
            
            // مسیریابی
            cmd.contains("مسیر") || cmd.contains("ببر") || cmd.contains("برو") -> {
                handleNavigationCommand(context, cmd)
            }
            
            else -> "متوجه نشدم. مثال: 'آهنگ شاد پخش کن' یا 'مسیریابی به تهران'"
        }
    }
    
    private fun handleMusicCommand(context: Context, cmd: String): String {
        val mood = when {
            cmd.contains("شاد") -> "شاد"
            cmd.contains("غمگین") -> "غمگین"
            cmd.contains("عاشقانه") -> "عاشقانه"
            cmd.contains("سنتی") -> "سنتی"
            cmd.contains("انرژی") || cmd.contains("پرانرژی") -> "انرژی"
            else -> "تصادفی"
        }
        
        val intent = Intent(context, ImprovedMusicActivity::class.java).apply {
            putExtra("AI_MOOD", mood)
            putExtra("AUTO_START", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        
        return "🎵 در حال پخش موزیک $mood..."
    }
    
    private fun handleNavigationCommand(context: Context, cmd: String): String {
        val dest = cmd
            .replace("مسیریابی", "")
            .replace("مسیر", "")
            .replace("ببر", "")
            .replace("برو", "")
            .replace("به", "")
            .trim()
        
        if (dest.length < 3) {
            return "❌ مقصد مشخص نیست. مثال: 'مسیریابی به تهران'"
        }
        
        // val intent = Intent(context, NavigationActivity::class.java).apply {
            putExtra("AI_DESTINATION", dest)
            putExtra("AI_VOICE", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        // context.startActivity(intent)
        
        return "🗺️ مسیریابی به $dest شروع شد"
    }
}
