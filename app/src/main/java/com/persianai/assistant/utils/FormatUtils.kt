package com.persianai.assistant.utils

object FormatUtils {

    fun formatMoney(amount: Double): String {
        return String.format("%,.0f", amount)
    }

    fun formatMoney(amount: Long): String {
        return String.format("%,d", amount)
    }

    fun formatMoneyWithUnit(amount: Double): String {
        return String.format("%,.0f تومان", amount)
    }

    fun formatMoneyWithUnit(amount: Long): String {
        return String.format("%,d تومان", amount)
    }

    fun formatCurrencyHumanReadable(amount: Long): String {
        return when {
            amount >= 1_000_000 -> "${amount / 1_000_000} میلیون تومان"
            amount >= 1_000 -> "${amount / 1_000} هزار تومان"
            else -> "$amount تومان"
        }
    }
}
