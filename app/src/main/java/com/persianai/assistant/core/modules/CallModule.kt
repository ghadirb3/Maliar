package com.persianai.assistant.core.modules

import android.content.Context
import com.persianai.assistant.call.CallContactSelectionDialogue
import com.persianai.assistant.core.AIIntentRequest
import com.persianai.assistant.core.AIIntentResult
import com.persianai.assistant.core.intent.AIIntent
import com.persianai.assistant.core.intent.CallSmartIntent
import com.persianai.assistant.utils.TTSHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallModule(context: Context) : BaseModule(context) {
    override val moduleName: String = "Call"
    private val scope = CoroutineScope(Dispatchers.Main)
    private val ttsHelper = TTSHelper(context)

    override suspend fun canHandle(intent: AIIntent): Boolean {
        return intent is CallSmartIntent
    }

    override suspend fun execute(request: AIIntentRequest, intent: AIIntent): AIIntentResult {
        if (intent is CallSmartIntent) {
            val contactName = intent.contactName
            if (!contactName.isNullOrBlank()) {
                scope.launch {
                    val dialogue = CallContactSelectionDialogue(context, ttsHelper)
                    dialogue.startContactSelection(contactName)
                }
                return createResult(
                    text = "در حال بررسی مخاطبین...",
                    intentName = intent.name,
                    actionType = "call.lookup"
                )
            }
        }

        return createResult(
            text = "دستور تماس نامفهوم است.",
            intentName = intent.name,
            success = false
        )
    }
}
