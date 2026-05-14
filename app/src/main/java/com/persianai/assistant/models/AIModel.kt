package com.persianai.assistant.models

enum class AIModel(val modelId: String) {
    // مدل‌های قبلی را نگه دارید و این‌ها را اضافه یا اصلاح کنید:
    LIARA_GPT5_NANO("gpt-5-nano"),
    LIARA_GEMINI_FLASH("gemini-2.0-flash"),
    GAPGPT_GPT5_NANO("gpt-5-nano"),
    GAPGPT_WHISPER("whisper-1"),
    GAPGPT_TTS_MINI("gpt-4o-mini-tts"),
    
    // اگر در فایل اصلی مدل‌های دیگری مثل این‌ها دارید، حتما نگه دارید:
    OFFLINE_VOSK("vosk-model-fa"),
    ONLINE_OPENAI("gpt-4")
}
