package com.persianai.assistant.core.modules

import android.content.Context
import com.persianai.assistant.core.AIIntentRequest
import com.persianai.assistant.core.AIIntentResult
import com.persianai.assistant.core.intent.AIIntent
import com.persianai.assistant.core.intent.CallSmartIntent
import com.persianai.assistant.call.CallContactSelectionDialogue
import com.persianai.assistant.core.tts.TTSHelper
import com.persianai.assistant.core.voice.UnifiedVoiceEngine
import com.persianai.assistant.data.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallModule(context: Context) : BaseModule(context) {
    override val moduleName: String = "Call"
    private val scope = CoroutineScope(Dispatchers.Main)

    // این موارد باید از تزریق وابستگی یا به صورت دستی مقداردهی شوند
    private lateinit var voiceEngine: UnifiedVoiceEngine
    private lateinit var ttsHelper: TTSHelper
    private lateinit var contactsRepository: ContactsRepository

    override fun canHandle(intent: AIIntent): Boolean {
        return intent is CallSmartIntent
    }

    override fun execute(request: AIIntentRequest, intent: AIIntent): AIIntentResult {
        if (intent is CallSmartIntent) {
            val contactName = intent.contactName
            if (!contactName.isNullOrEmpty()) {
                scope.launch {
                    val dialogue = CallContactSelectionDialogue(context, voiceEngine, ttsHelper, contactsRepository)
                    dialogue.startContactSelection(contactName)
                }
                return AIIntentResult(true, "در حال بررسی مخاطبین...")
            }
        }
        return AIIntentResult(false, "دستور تماس نامفهوم است.")
    }
}
