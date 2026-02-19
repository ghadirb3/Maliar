package com.persianai.assistant.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * مدل مخاطب برای سیستم تماس مالیار
 */
@Parcelize
data class Contact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val score: Float = 0f // امتیاز تطابق برای جستجو
) : Parcelable {
    
    /**
     * نمایش نام و شماره به صورت فرمت شده
     */
    fun getDisplayName(): String = name
    
    /**
     * نمایش شماره تلفن به صورت فرمت شده
     */
    fun getFormattedNumber(): String {
        return try {
            // فرمت‌بندی شماره تلفن ایرانی
            val cleanNumber = phoneNumber.replace("[^0-9]".toRegex(), "")
            when {
                cleanNumber.startsWith("0098") -> "+98${cleanNumber.substring(4)}"
                cleanNumber.startsWith("09") -> "+98${cleanNumber.substring(1)}"
                cleanNumber.startsWith("98") -> "+98${cleanNumber.substring(2)}"
                else -> phoneNumber
            }
        } catch (e: Exception) {
            phoneNumber
        }
    }
    
    /**
     * بررسی اینکه آیا این مخاطب با عبارت جستجو مطابقت دارد
     */
    fun matchesQuery(query: String): Boolean {
        val cleanQuery = query.lowercase().trim()
        val cleanName = name.lowercase()
        val cleanNumber = phoneNumber.replace("[^0-9]".toRegex(), "")
        val cleanQueryNumber = query.replace("[^0-9]".toRegex(), "")
        
        return cleanName.contains(cleanQuery) || 
               cleanNumber.contains(cleanQueryNumber) ||
               cleanName.contains(cleanQuery.replace(" ", ""))
    }
    
    /**
     * محاسبه امتیاز تطابق با عبارت جستجو
     */
    fun calculateMatchScore(query: String): Float {
        val cleanQuery = query.lowercase().trim()
        val cleanName = name.lowercase()
        
        // تطابق کامل نام
        if (cleanName == cleanQuery) return 1.0f
        
        // تطابق کامل شماره
        val cleanNumber = phoneNumber.replace("[^0-9]".toRegex(), "")
        val cleanQueryNumber = query.replace("[^0-9]".toRegex(), "")
        if (cleanNumber == cleanQueryNumber) return 0.9f
        
        // تطابق جزئی نام
        if (cleanName.contains(cleanQuery)) {
            val ratio = cleanQuery.length.toFloat() / cleanName.length
            return 0.5f + ratio * 0.3f
        }
        
        // تطابق جزئی شماره
        if (cleanNumber.contains(cleanQueryNumber) && cleanQueryNumber.isNotEmpty()) {
            val ratio = cleanQueryNumber.length.toFloat() / cleanNumber.length
            return 0.4f + ratio * 0.3f
        }
        
        return 0f
    }
    
    companion object {
        /**
         * ایجاد یک نمونه برای تست
         */
        fun createTestContact(name: String, phone: String): Contact {
            return Contact(
                id = "test_${System.currentTimeMillis()}",
                name = name,
                phoneNumber = phone,
                score = 0f
            )
        }
    }
}
