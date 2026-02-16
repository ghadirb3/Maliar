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

/**
 * کلاس کمکی برای مدیریت مدل‌های ترکیبی (استاتیک + داینامیک)
 */
object ModelManager {
    /**
     * دریافت تمام مدل‌های موجود (استاتیک + داینامیک)
     */
    fun getAllModels(context: Context?): List<ModelWrapper> {
        val staticModels = AIModel.values().map { ModelWrapper.from(it) }
        val dynamicModels = DynamicAIModel.getDynamicModels(context).map { ModelWrapper.from(it) }
        
        return (staticModels + dynamicModels).sortedBy { it.priority }
    }
    
    /**
     * پیدا کردن مدل بر اساس modelId
     */
    fun findModel(modelId: String, context: Context?): ModelWrapper? {
        // اول در مدل‌های استاتیک بگرد
        AIModel.values().find { it.modelId.equals(modelId, ignoreCase = true) }
            ?.let { return ModelWrapper.from(it) }
        
        // بعد در مدل‌های داینامیک بگرد
        DynamicAIModel.findByModelId(modelId, context)
            ?.let { return ModelWrapper.from(it) }
        
        return null
    }
    
    /**
     * دریافت لیست اولویت مدل‌ها از remote config
     */
    fun getModelPriority(context: Context?): List<ModelWrapper> {
        val ctx = context
        if (ctx != null) {
            try {
                val remote = RemoteAIConfigManager.getInstance(ctx).getModelPriority()
                if (remote.isNotEmpty()) return remote.map { ModelWrapper.from(it) }
            } catch (_: Exception) {
            }
        }
        val dynamicModels = DynamicAIModel.getDynamicModels(context)
        
        if (dynamicModels.isNotEmpty()) {
            return dynamicModels.map { ModelWrapper.from(it) }
        }
        
        // fallback به مدل‌های استاتیک
        return listOf(
            AIModel.LIARA_GPT_4O_MINI,
            AIModel.GPT_4O_MINI,
            AIModel.TINY_LLAMA_OFFLINE
        ).map { ModelWrapper.from(it) }
    }
}

/**
 * کلاس wrapper برای یکپارچه‌سازی مدل‌های استاتیک و داینامیک
 */
sealed class ModelWrapper {
    abstract val modelId: String
    abstract val displayName: String
    abstract val provider: AIProvider
    abstract val description: String
    abstract val maxTokens: Int
    abstract val priority: Int
    abstract val baseUrl: String?
    
    data class StaticModel(val aiModel: AIModel) : ModelWrapper() {
        override val modelId = aiModel.modelId
        override val displayName = aiModel.displayName
        override val provider = aiModel.provider
        override val description = aiModel.description
        override val maxTokens = aiModel.maxTokens
        override val priority = 1000 // priority بالاتر برای مدل‌های استاتیک
        override val baseUrl = null
        
        fun unwrap(): AIModel = aiModel
    }
    
    data class DynamicModel(val dynamicModel: DynamicAIModel) : ModelWrapper() {
        override val modelId = dynamicModel.modelId
        override val displayName = dynamicModel.displayName
        override val provider = dynamicModel.provider
        override val description = dynamicModel.description
        override val maxTokens = dynamicModel.maxTokens
        override val priority = dynamicModel.priority
        override val baseUrl = dynamicModel.baseUrl
        
        fun unwrap(): DynamicAIModel = dynamicModel
    }
    
    companion object {
        fun from(aiModel: AIModel) = StaticModel(aiModel)
        fun from(dynamicModel: DynamicAIModel) = DynamicModel(dynamicModel)
    }
}
