package com.persianai.assistant.core.modules

import android.content.Context
import android.content.Intent
import android.util.Log
// import com.persianai.assistant.activities.VoiceNavigationAssistantActivity
import com.persianai.assistant.core.AIIntentRequest
import com.persianai.assistant.core.AIIntentResult
import com.persianai.assistant.core.intent.AIIntent
import com.persianai.assistant.core.intent.NavigationSearchIntent
import com.persianai.assistant.core.intent.NavigationStartIntent

class NavigationModule(context: Context) : BaseModule(context) {
    override val moduleName: String = "Navigation"

    override suspend fun canHandle(intent: AIIntent): Boolean {
        return intent is NavigationSearchIntent || intent is NavigationStartIntent
    }

    override suspend fun execute(request: AIIntentRequest, intent: AIIntent): AIIntentResult {
        return when (intent) {
            is NavigationSearchIntent -> handle(request, intent.destination ?: intent.rawText)
            is NavigationStartIntent -> handle(request, intent.destination ?: intent.rawText)
            else -> createResult("نوع Intent نشناختهشده", intent.name, false)
        }
    }

    private fun handle(request: AIIntentRequest, destination: String?): AIIntentResult {
        val dest = destination?.trim().orEmpty()
        logAction("NAV", "dest=$dest")

        return try {
            // val i = Intent(context, VoiceNavigationAssistantActivity::class.java).apply {
//                putExtra("prefill_text", dest)
//                flags = Intent.FLAG_ACTIVITY_NEW_TASK
//            }
//            context.startActivity(i)

            createResult(
                text = if (dest.isBlank()) "🗺️ دستیار مسیریابی باز شد" else "🗺️ دستیار مسیریابی باز شد برای: $dest",
                intentName = "navigation",
                actionType = "open_navigation",
                actionData = dest
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error opening navigation", e)
            createResult(
                text = "❌ خطا در باز کردن مسیریابی",
                intentName = "navigation",
                success = false
            )
        }
    }
}
