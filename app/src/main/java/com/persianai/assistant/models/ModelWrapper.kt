package com.persianai.assistant.models

/**
 * Wrapper برای مدل‌های هوش مصنوعی (استاتیک و داینامیک)
 */
sealed class ModelWrapper {
    abstract fun unwrap(): AIModel
    
    data class StaticModel(val model: AIModel) : ModelWrapper() {
        override fun unwrap(): AIModel = model
    }
    
    data class DynamicModel(val dynamicModel: DynamicAIModel) : ModelWrapper() {
        override fun unwrap(): AIModel {
            // تبدیل مدل داینامیک به مدل استاتیک نزدیک‌ترین معادل
            return when (dynamicModel.provider) {
                AIProvider.LIARA -> {
                    when {
                        dynamicModel.modelId.contains("gpt-5-nano") -> AIModel.LIARA_GPT_5_NANO
                        dynamicModel.modelId.contains("gpt-4o-mini") -> AIModel.LIARA_GPT_4O_MINI
                        else -> AIModel.LIARA_GPT_5_NANO
                    }
                }
                AIProvider.GAPGPT -> {
                    when {
                        dynamicModel.modelId.contains("gpt-5-nano") -> AIModel.GAPGPT_GPT_5_NANO
                        dynamicModel.modelId.contains("gpt-4o-mini") -> AIModel.GAPGPT_GPT_4O_MINI
                        else -> AIModel.GAPGPT_GPT_5_NANO
                    }
                }
                AIProvider.OPENAI -> AIModel.GPT_4O_MINI
                else -> AIModel.GPT_4O_MINI
            }
        }
    }
}
