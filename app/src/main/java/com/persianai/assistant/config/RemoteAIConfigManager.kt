package com.persianai.assistant.config

import com.persianai.assistant.models.AIModel

object RemoteAIConfigManager {
    // --- اولویت‌بندی چت متنی ---
    val textModelPriority = listOf(
        AIModel.LIARA_GPT5_NANO,      // اولویت ۱: لیارا (طبق خواسته شما)
        AIModel.GAPGPT_GPT5_NANO      // اولویت ۲: GapGPT
    )

    // --- اولویت‌بندی صدا به متن (Speech-to-Text) ---
    // طبق مستندات GapGPT (مدل whisper-1) و لیارا (gemini)
    val sttModelPriority = listOf(
        AIModel.LIARA_GEMINI_FLASH,   // اولویت ۱: لیارا (سرعت بالا در پردازش صوت)
        AIModel.GAPGPT_WHISPER        // اولویت ۲: مدل تخصصی Whisper در GapGPT
    )

    // --- اولویت‌بندی متن به صدا (Text-to-Speech) ---
    // طبق مستندات جدید GapGPT (مدل gpt-4o-mini-tts)
    val ttsModelPriority = listOf(
        AIModel.GAPGPT_TTS_MINI,      // اولویت ۱: مدل جدید GapGPT برای تولید صدای طبیعی
        AIModel.LIARA_GPT5_NANO       // اولویت ۲: لیارا (بخش متنی)
    )

    // متدهای کمکی برای دسترسی سریع
    fun getPreferredTextModel() = textModelPriority.first()
    fun getPreferredSTTModel() = sttModelPriority.first()
    fun getPreferredTTSModel() = ttsModelPriority.first()
}
