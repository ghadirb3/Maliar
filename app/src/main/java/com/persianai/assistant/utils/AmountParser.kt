package com.persianai.assistant.utils

object AmountParser {

    private val persianDigits = mapOf(
        '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
        '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9'
    )

    fun parse(input: String?): Double {
        if (input.isNullOrBlank()) return 0.0
        val normalized = input.trim()
            .replace(",", "")
            .replace("،", "")
            .replace("٬", "")
            .replace(" ", "")
            .map { persianDigits[it] ?: it }
            .joinToString("")
        return normalized.toDoubleOrNull() ?: 0.0
    }
}
