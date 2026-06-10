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
            AIModel.GAPGPT_GPT_5_3_CHAT_LATEST,    // اولویت ۱: gpt-5.3-chat-latest از GAPGPT (بهترین مدل)
            AIModel.GAPGPT_GPT_4O_MINI,            // اولویت ۲: gpt-4o-mini از GAPGPT
            AIModel.GAPGPT_GPT_5_NANO,             // اولویت ۳: gpt-5-nano از GAPGPT (400 error)
            AIModel.LIARA_GPT_5_NANO,              // اولویت ۴: gpt-5-nano از لیارا
            AIModel.LIARA_GEMINI_FLASH,            // اولویت ۵: gemini-2.0-flash از لیارا
            AIModel.LIARA_GPT_4O_MINI,             // اولویت ۶: gpt-4o-mini از لیارا
            AIModel.GPT_4O_MINI,                   // fallback عمومی OpenAI
            AIModel.TINY_LLAMA_OFFLINE             // آخرین گزینه: آفلاین
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
