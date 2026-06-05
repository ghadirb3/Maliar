package com.persianai.assistant.utils

object TextParsingUtils {

    private val persianArabicToEnglish = mapOf(
        '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
        '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9'
    )

    fun persianToEnglishDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) sb.append(persianArabicToEnglish[ch] ?: ch)
        return sb.toString()
    }

    fun extractJsonFromResponse(response: String): String {
        val startIdx = response.indexOf('{')
        val endIdx = response.lastIndexOf('}')
        return if (startIdx >= 0 && endIdx > startIdx) {
            response.substring(startIdx, endIdx + 1)
        } else {
            response
        }
    }

    fun parseDateStringToMillis(dateStr: String): Long {
        return try {
            val parts = dateStr.split("/")
            if (parts.size == 3) {
                val year = parts[0].toInt()
                val month = parts[1].toInt()
                val day = parts[2].toInt()
                val calendar = java.util.Calendar.getInstance()
                calendar.set(year, month - 1, day, 0, 0, 0)
                calendar.timeInMillis
            } else {
                System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
