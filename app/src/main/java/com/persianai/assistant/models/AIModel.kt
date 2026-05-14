package com.persianai.assistant.models

enum class AIModel(val modelName: String, val provider: String) {
    // Liara Models
    LIARA_GPT5_NANO("gpt-5-nano", "liara"),
    LIARA_GEMINI_FLASH("gemini-2.0-flash", "liara"),
    
    // GapGPT Models
    GAPGPT_GPT5_NANO("gpt-5-nano", "gapgpt"),
    GAPGPT_WHISPER("whisper-1", "gapgpt"),
    GAPGPT_TTS_MINI("gpt-4o-mini-tts", "gapgpt"), // مدل جدید برای صوت به متن
    
    // سایر مدل‌های قبلی پروژه را اینجا حفظ کن (اگر بودند)
    OPENAI_GPT4("gpt-4", "openai"),
    GOOGLE_GEMINI("gemini-pro", "google")
}
