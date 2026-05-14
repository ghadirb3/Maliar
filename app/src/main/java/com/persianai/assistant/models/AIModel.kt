package com.persianai.assistant.models

enum class AIProvider {
    OFFLINE, ONLINE, LIARA, GAPGPT, OPENAI, GOOGLE, ANTHROPIC
}

enum class AIModel(
    val modelId: String,
    val displayName: String,
    val provider: AIProvider,
    val description: String,
    val maxTokens: Int
) {
    // Offline Models
    TINY_LLAMA_OFFLINE("tinyllama-offline", "TinyLlama Offline", AIProvider.OFFLINE, "A small offline model", 1024),
    PHI3_5_OFFLINE("phi3.5-offline", "Phi-3.5 Offline", AIProvider.OFFLINE, "A medium offline model", 2048),
    GEMMA_2B_OFFLINE("gemma-2b-offline", "Gemma 2B Offline", AIProvider.OFFLINE, "A small Google model", 2048),

    // Online Models
    QWEN_2_5_1B5("qwen-2.5-1.8b", "Qwen 2.5 1.8B", AIProvider.ONLINE, "Qwen 2.5 1.8B", 4096),
    LLAMA_3_2_1B("llama-3-8b", "Llama 3 8B", AIProvider.ONLINE, "Meta Llama 3 8B", 8192),
    LLAMA_3_2_3B("llama-3-8b", "Llama 3 8B", AIProvider.ONLINE, "Meta Llama 3 8B", 8192),
    MIXTRAL_8X7B("mixtral-8x7b", "Mixtral 8x7B", AIProvider.ONLINE, "Mistral Mixtral 8x7B", 32768),
    LLAMA_3_3_70B("llama-3-70b", "Llama 3 70B", AIProvider.ONLINE, "Meta Llama 3 70B", 128000),
    DEEPSEEK_R1T2("deepseek-coder-v2-lite", "DeepSeek Coder V2 Lite", AIProvider.ONLINE, "DeepSeek Coder V2 Lite", 32768),
    GPT_4O_MINI("gpt-4o-mini", "GPT-4o Mini", AIProvider.ONLINE, "OpenAI GPT-4o Mini", 128000),
    GPT_4O("gpt-4o", "GPT-4o", AIProvider.ONLINE, "OpenAI GPT-4o", 128000),
    CLAUDE_HAIKU("claude-3-haiku", "Claude 3 Haiku", AIProvider.ONLINE, "Anthropic Claude 3 Haiku", 200000),
    CLAUDE_SONNET("claude-3-sonnet", "Claude 3 Sonnet", AIProvider.ONLINE, "Anthropic Claude 3 Sonnet", 200000),

    // Liara Models (Fixed names for RemoteAIConfigManager)
    LIARA_GPT_5_NANO("gpt-5-nano", "Liara GPT-5 Nano", AIProvider.LIARA, "Liara's GPT-5 Nano model", 4096),
    LIARA_GPT_4O_MINI("gpt-4o-mini", "Liara GPT-4o Mini", AIProvider.LIARA, "Liara's GPT-4o Mini model", 128000),
    LIARA_GEMINI_FLASH("gemini-2.0-flash", "Liara Gemini Flash", AIProvider.LIARA, "Liara Gemini 2.0 Flash", 8192),

    // GapGPT Models
    GAPGPT_GPT5_NANO("gpt-5-nano", "GapGPT GPT-5 Nano", AIProvider.GAPGPT, "GapGPT's GPT-5 Nano model", 4096),
    GAPGPT_WHISPER("whisper-1", "GapGPT Whisper", AIProvider.GAPGPT, "GapGPT's Whisper model", 1500),
    GAPGPT_TTS_MINI("gpt-4o-mini-tts", "GapGPT GPT-4o Mini TTS", AIProvider.GAPGPT, "GapGPT's TTS model", 4096),

    // Compatibility Models (Added to prevent errors in ConfigManager)
    IVIRA_GPT5_NANO("gpt-5-nano", "Ivira GPT-5 Nano", AIProvider.ONLINE, "Legacy Ivira Model", 4096),
    IVIRA_GPT5_MINI("gpt-4o-mini", "Ivira GPT-5 Mini", AIProvider.ONLINE, "Legacy Ivira Model", 4096),
    AIML_GPT_35("gpt-3.5-turbo", "AIML GPT 3.5", AIProvider.ONLINE, "AIML Model", 4096)
}
