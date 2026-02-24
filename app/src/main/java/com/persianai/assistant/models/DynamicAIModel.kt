package com.persianai.assistant.models

import android.content.Context
import com.persianai.assistant.config.RemoteAIConfigManager

/**
 * مدل هوش مصنوعی داینامیک که از remote config خوانده می‌شود
 */
data class DynamicAIModel(
    val modelId: String,
    val displayName: String,
    val provider: AIProvider,
    val description: String,
    val maxTokens: Int,
    val baseUrl: String? = null,
    val priority: Int = 999,
    val enabled: Boolean = true
) {
    companion object {
        /**
         * دریافت تمام مدل‌های داینامیک از remote config
         */
        fun getDynamicModels(context: Context?): List<DynamicAIModel> {
            return context?.let { ctx ->
                try {
                    val config = RemoteAIConfigManager.getInstance(ctx).loadCached()
                    config?.ai_text_models?.filter { it.enabled }
                        ?.sortedBy { it.priority ?: 999 }
                        ?.mapNotNull { modelConfig ->
                            createFromConfig(modelConfig)
                        } ?: emptyList()
                } catch (e: Exception) {
                    android.util.Log.e("DynamicAIModel", "Error loading dynamic models", e)
                    emptyList()
                }
            } ?: emptyList()
        }
        
        /**
         * ایجاد مدل داینامیک از تنظیمات remote config
         */
        private fun createFromConfig(config: com.persianai.assistant.config.RemoteAIConfigManager.ModelConfig): DynamicAIModel? {
            return try {
                val provider = try {
                    AIProvider.valueOf(config.provider.uppercase())
                } catch (e: IllegalArgumentException) {
                    // اگر provider تعریف نشده بود، آن را به عنوان CUSTOM در نظر بگیر
                    AIProvider.CUSTOM
                }
                
                DynamicAIModel(
                    modelId = config.name,
                    displayName = "${config.name} (${config.provider})",
                    provider = provider,
                    description = "مدل داینامیک از ${config.provider}",
                    maxTokens = 32000, // maxTokens پیش‌فرض
                    baseUrl = config.base_url,
                    priority = config.priority ?: 999,
                    enabled = config.enabled
                )
            } catch (e: Exception) {
                android.util.Log.e("DynamicAIModel", "Failed to create dynamic model: ${config.name}", e)
                null
            }
        }
        
        /**
         * پیدا کردن مدل داینامیک بر اساس modelId
         */
        fun findByModelId(modelId: String, context: Context?): DynamicAIModel? {
            return getDynamicModels(context).find { it.modelId.equals(modelId, ignoreCase = true) }
        }
        
        /**
         * تبدیل به AIModel استاتیک اگر وجود داشت
         */
        fun toStaticModelIfExists(modelId: String, context: Context?): AIModel? {
            // اول در مدل‌های استاتیک بگرد
            return AIModel.values().find { it.modelId.equals(modelId, ignoreCase = true) }
                // اگر پیدا نشد، بررسی کن آیا مدل داینامیک با provider مشابه وجود دارد
                ?: context?.let { ctx ->
                    val dynamicModel = findByModelId(modelId, ctx)
                    dynamicModel?.let { dm ->
                        // پیدا کردن اولین مدل استاتیک با همین provider
                        AIModel.values().find { it.provider == dm.provider }
                    }
                }
        }
    }
}
