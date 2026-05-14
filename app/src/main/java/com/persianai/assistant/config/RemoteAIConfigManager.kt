package com.persianai.assistant.config

import com.persianai.assistant.models.AIModel

object RemoteAIConfigManager {
    // اولویت برای چت متنی
    val textModelPriority = listOf(
        AIModel.LIARA_GPT5_NANO,     // اولویت اول: لیارا
        AIModel.GAPGPT_GPT5_NANO     // اولویت دوم: GapGPT
    )

    // اولویت برای صوت به متن (STT)
    val sttModelPriority = listOf(
        AIModel.LIARA_GEMINI_FLASH,  // اولویت اول: لیارا (Gemini)
        AIModel.GAPGPT_TTS_MINI,     // اولویت دوم: مدل جدید GapGPT
        AIModel.GAPGPT_WHISPER       // اولویت سوم: Whisper
    )

    fun getPreferredTextModel(): AIModel = textModelPriority.first()
    fun getPreferredSTTModel(): AIModel = sttModelPriority.first()
}
