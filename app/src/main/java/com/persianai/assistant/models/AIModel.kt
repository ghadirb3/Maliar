package com.persianai.assistant.models

import com.google.gson.annotations.SerializedName

/**
 * مدل‌های هوش مصنوعی پشتیبانی شده
 */
enum class AIModel(
    val modelId: String,
    val displayName: String,
    val provider: AIProvider,
    val description: String,
    val maxTokens: Int
) {
    // حالت فقط آفلاین - TinyLlama
    TINY_LLAMA_OFFLINE(
        "tinyllama-1.1b-offline",
        "Tiny Llama (آفلاین)",
        AIProvider.LOCAL,
        "مدل سبک آفلاین برای پاسخ‌های سریع",
        2048
    ),
    PHI3_5_OFFLINE(
        "phi3.5-offline", 
        "Phi-3.5 (آفلاین)",
        AIProvider.LOCAL,
        "مدل آفلاین با کیفیت بالا",
        4096
    ),
    GEMMA_2B_OFFLINE(
        "gemma-2b-offline",
        "Gemma 2B (آفلاین)",
        AIProvider.LOCAL,
        "مدل آفلاین گوگل",
        4096
    ),
    QWEN_2_5_1B5(
        "Qwen2.5-1.5B-Instruct",
        "Qwen 2.5 1.5B (OpenRouter)",
        AIProvider.OPENROUTER,
        "مدل سبک چینی برای کارهای عمومی",
        8000
    ),
    LLAMA_3_2_1B(
        "meta-llama/llama-3.2-1b-instruct",
        "Llama 3.2 1B (OpenRouter)",
        AIProvider.OPENROUTER,
        "مدل سبک متا برای مکالمه عمومی",
        8000
    ),
    LLAMA_3_2_3B(
        "meta-llama/llama-3.2-3b-instruct",
        "Llama 3.2 3B (OpenRouter)",
        AIProvider.OPENROUTER,
        "مدل متا با کیفیت بهتر",
        8000
    ),
    MIXTRAL_8X7B(
        "mistralai/mixtral-8x7b-instruct",
        "Mixtral 8x7B (OpenRouter)",
        AIProvider.OPENROUTER,
        "مدل قدرتمند و چندزبانه",
        32000
    ),
    LLAMA_3_3_70B(
        "meta-llama/llama-3.3-70b-instruct",
        "Llama 3.3 70B (OpenRouter)",
        AIProvider.OPENROUTER,
        "مدل بسیار قدرتمند متا",
        8000
    ),
    DEEPSEEK_R1T2(
        "deepseek/deepseek-r1-t2-chimera",
        "DeepSeek R1T2 (OpenRouter)",
        AIProvider.OPENROUTER,
        "تمرکز بر استدلال و هزینه کم",
        8000
    ),
    GPT_4O_MINI(
        "gpt-4o-mini",
        "GPT-4o Mini (OpenAI)",
        AIProvider.OPENAI,
        "مدل سریع و ارزان OpenAI",
        16000
    ),
    GPT_4O(
        "gpt-4o",
        "GPT-4o (OpenAI)",
        AIProvider.OPENAI,
        "مدل قدرتمند OpenAI",
        128000
    ),
    CLAUDE_HAIKU(
        "claude-3-haiku-20240307",
        "Claude 3 Haiku",
        AIProvider.ANTHROPIC,
        "مدل سریع انتروپیک",
        200000
    ),
    CLAUDE_SONNET(
        "claude-3-5-sonnet-20241022",
        "Claude 3.5 Sonnet",
        AIProvider.ANTHROPIC,
        "مدل قوی انتروپیک",
        200000
    ),
    LIARA_GPT_4O_MINI(
        "gpt-4o-mini",
        "GPT-4o Mini (Liara)",
        AIProvider.LIARA,
        "مدل GPT-4o Mini از سرویس لیارا",
        16000
    ),
    LIARA_GPT_5_NANO(
        "openai/gpt-5-nano",
        "GPT-5 Nano (Liara)",
        AIProvider.LIARA,
        "مدل GPT-5 Nano از لیارا",
        32000
    ),
    IVIRA_GPT5_NANO(
        "gpt-5-nano",
        "GPT-5 Nano (Ivira)",
        AIProvider.IVIRA,
        "مدل GPT-5 Nano از Ivira",
        32000
    ),
    IVIRA_GPT5_MINI(
        "gpt-5-mini",
        "GPT-5 Mini (Ivira)",
        AIProvider.IVIRA,
        "مدل GPT-5 Mini از Ivira",
        32000
    ),
    AVALAI_GEMINI_FLASH(
        "gemini-2.0-flash-exp",
        "Gemini 2.0 Flash (Avalai)",
        AIProvider.AVALAI,
        "مدل سریع گوگل از Avalai",
        8000
    ),
    AIML_GPT_35(
        "claude-3-5-haiku-20241022",
        "Claude 3.5 Haiku",
        AIProvider.ANTHROPIC,
        "نسخه سریع و مقرون به صرفه Claude",
        200000
    ),
    GPT_35_TURBO(
        "gpt-3.5-turbo",
        "GPT-3.5 Turbo",
        AIProvider.OPENAI,
        "مدل اقتصادی OpenAI",
        4000
    ),
    GAPGPT_GPT_4O_MINI(
        "gpt-4o-mini",
        "GPT-4o Mini (GAPGPT)",
        AIProvider.GAPGPT,
        "مدل GPT-4o Mini از GAPGPT",
        16000
    ),
    GAPGPT_GPT_5_NANO(
        "gpt-5-nano",
        "GPT-5 Nano (GAPGPT)",
        AIProvider.GAPGPT,
        "مدل GPT-5 Nano از GAPGPT",
        32000
    ),
    GAPGPT_GPT_4O_MINI_TEST(
        "gpt-4o-mini",
        "GPT-4o Mini Test (GAPGPT)",
        AIProvider.GAPGPT,
        "مدل تست GPT-4o Mini از GAPGPT",
        16000
    ),
    GAPGPT_DEEPSEEK_CHAT(
        "deepseek-chat",
        "DeepSeek Chat (GAPGPT)",
        AIProvider.GAPGPT,
        "مدل DeepSeek Chat از GAPGPT",
        8000
    ),
    GAPGPT_GROK_3_MINI(
        "grok-3-mini",
        "Grok 3 Mini (GAPGPT)",
        AIProvider.GAPGPT,
        "مدل Grok 3 Mini از GAPGPT",
        8000
    ),
    GAPGPT_GEMINI_2_FLASH(
        "gemini-2.0-flash",
        "Gemini 2.0 Flash (GAPGPT)",
        AIProvider.GAPGPT,
        "مدل Gemini 2.0 Flash از GAPGPT",
        8000
    ),
    GAPGPT_GPT_5_2_CHAT_LATEST(
        "gpt-5.2-chat-latest",
        "GPT-5.2 Chat Latest (GAPGPT)",
        AIProvider.GAPGPT,
        "مدل GPT-5.2 Chat Latest از GAPGPT",
        32000
    ),
    GAPGPT_DEEPSEEK_V3(
        "gapgpt-deepseek-v3",
        "DeepSeek V3 (GAPGPT)",
        AIProvider.GAPGPT,
        "مدل DeepSeek V3 از GAPGPT",
        8000
    );

    companion object {
        fun fromModelId(modelId: String): AIModel? {
            return values().find { it.modelId == modelId }
        }

        fun getDefaultModel(): AIModel = GPT_4O_MINI
    }
}

/**
 * ارائه‌دهندگان سرویس هوش مصنوعی
 */
enum class AIProvider {
    AIML,
    GLADIA,
    OPENAI,
    ANTHROPIC,
    OPENROUTER,
    LIARA,
    AVALAI,
    IVIRA,
    GAPGPT,
    LOCAL,
    CUSTOM
}

/**
 * کلید API
 */
data class APIKey(
    val provider: AIProvider,
    val key: String,
    val baseUrl: String? = null,
    val isActive: Boolean = true
)

/**
 * پیام چت
 */
data class ChatMessage(
    val id: Long = 0,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val audioPath: String? = null,
    val isError: Boolean = false
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * درخواست چت
 */
data class ChatRequest(
    val model: String,
    val messages: List<Map<String, String>>,
    val temperature: Double = 0.7,
    @SerializedName("max_tokens")
    val maxTokens: Int = 4096,
    val stream: Boolean = false,
    // Add optional fields that some APIs might expect
    val top_p: Double? = null,
    val frequency_penalty: Double? = null,
    val presence_penalty: Double? = null
)

/**
 * پاسخ چت
 */
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice>? = null,
    val usage: Usage? = null,
    // Allow alternative response formats
    val response: String? = null,  // Some APIs might use this field
    val text: String? = null,      // Some APIs might use this field
    val content: String? = null    // Some APIs might use this field
)

data class Choice(
    val index: Int? = null,
    val message: Message? = null,
    @SerializedName("finish_reason")
    val finishReason: String? = null,
    // Alternative formats
    val text: String? = null,
    val content: String? = null
)

data class Message(
    val role: String? = null,
    val content: String? = null
)

data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/**
 * پاسخ Claude
 */
data class ClaudeResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<ClaudeContent>,
    val model: String,
    val usage: ClaudeUsage
)

data class ClaudeContent(
    val type: String,
    val text: String
)

data class ClaudeUsage(
    val inputTokens: Int,
    val outputTokens: Int
)
