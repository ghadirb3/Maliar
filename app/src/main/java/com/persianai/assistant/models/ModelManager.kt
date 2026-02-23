package com.persianai.assistant.models

import android.content.Context
import android.util.Log

/**
 * مدیر مدل‌های هوش مصنوعی (نسخه ساده‌شده)
 */
object ModelManager {
    private const val TAG = "ModelManager"
    
    /**
     * دریافت لیست اولویت مدل‌ها (شامل مدل‌های داینامیک و استاتیک)
     */
    fun getModelPriority(context: Context?): List<ModelWrapper> {
        val wrappers = mutableListOf<ModelWrapper>()
        
        // ابتدا مدل‌های داینامیک را اضافه کن
        try {
            val dynamicModels = DynamicAIModel.getDynamicModels(context)
            for (dynamicModel in dynamicModels) {
                wrappers.add(ModelWrapper.DynamicModel(dynamicModel))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load dynamic models", e)
        }
        
        // سپس مدل‌های استاتیک را با اولویت مشخص اضافه کن
        val staticPriority = listOf(
            AIModel.LIARA_GPT_5_NANO,
            AIModel.GAPGPT_GPT_5_NANO,
            AIModel.GAPGPT_DEEPSEEK_V3,
            AIModel.GPT_4O_MINI,
            AIModel.TINY_LLAMA_OFFLINE
        )
        
        for (model in staticPriority) {
            wrappers.add(ModelWrapper.StaticModel(model))
        }
        
        return wrappers
    }
    
    /**
     * دریافت تمام مدل‌های موجود
     */
    fun getAllModels(context: Context?): List<ModelWrapper> {
        return getModelPriority(context)
    }
}
