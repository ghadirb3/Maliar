package com.persianai.assistant.models

enum class AIModel(val modelId: String) {
    // مدل‌های لیارا
    LIARA_GPT5_NANO("gpt-5-nano"),
    LIARA_GEMINI_FLASH("gemini-2.0-flash"),

    // مدل‌های GapGPT (دقیقاً مطابق مستندات متنی که فرستادی)
    GAPGPT_GPT5_NANO("gpt-5-nano"),
    GAPGPT_WHISPER("whisper-1"),
    GAPGPT_TTS_MINI("gpt-4o-mini-tts"), // این مدل برای متن به صدا (TTS) در GapGPT است
    
    // مدل‌های قبلی
    OFFLINE_VOSK("vosk-model-fa")
}
