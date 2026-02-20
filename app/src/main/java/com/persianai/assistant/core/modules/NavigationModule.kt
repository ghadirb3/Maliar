package com.persianai.assistant.core.modules

import android.content.Context
import com.persianai.assistant.core.AIIntentRequest
import com.persianai.assistant.core.AIIntentResult
import com.persianai.assistant.core.intent.AIIntent

/**
 * DISABLED: Navigation feature removed
 * This module has been stubbed to remove navigation dependencies
 */
class NavigationModule(context: Context) : BaseModule(context) {
    override val moduleName: String = "Navigation"

    override suspend fun canHandle(intent: AIIntent): Boolean {
        return false // Navigation disabled
    }

    override suspend fun execute(request: AIIntentRequest, intent: AIIntent): AIIntentResult {
        return createResult("ویژگی مسیریابی موقتاً غیرفعال است", intent.name, false)
    }
}
