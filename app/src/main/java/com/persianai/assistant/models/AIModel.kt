package com.persianai.assistant.models

enum class AIModel(val modelId: String) {
    // مدل‌های قبلی را نگه دار و این‌ها را اضافه یا اصلاح کن:
    LIARA_GPT5_NANO("gpt-5-nano"),
    LIARA_GEMINI_FLASH("gemini-2.0-flash"),
    GAPGPT_GPT5_NANO("gpt-5-nano"),
    GAPGPT_WHISPER("whisper-1"),
    GAPGPT_TTS_MINI("gpt-4o-mini-tts"),
    
    // بقیه مدل‌های موجود در فایل اصلی‌ات مثل IVIRA و غیره را تغییر نده
}
