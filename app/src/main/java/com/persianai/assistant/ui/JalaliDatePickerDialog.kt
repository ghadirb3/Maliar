package com.persianai.assistant.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.NumberPicker
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.persianai.assistant.R
import com.persianai.assistant.utils.JalaliCalendar

/**
 * A minimal Jalali date picker dialog backed by JalaliCalendar.
 * Returns selected date as milliseconds via callback.
 * 
 * Enhanced to show Persian month names dynamically based on selection.
 */
class JalaliDatePickerDialog(private val ctx: Context) {

    data class Result(val year: Int, val month: Int, val day: Int)

    private val persianMonthNames = arrayOf(
        "فروردین", "اردیبهشت", "خرداد",
        "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر",
        "دی", "بهمن", "اسفند"
    )

    private fun getMaxDays(jy: Int, jm: Int): Int {
        return if (jm <= 6) 31 else if (jm <= 11) 30 else {
            // اسفند - check if leap year
            if (JalaliCalendar.isJalaliLeapYear(jy)) 30 else 29
        }
    }

    fun show(initialMillis: Long = System.currentTimeMillis(), onSelected: (Long) -> Unit) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = initialMillis }
        val j = JalaliCalendar(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))

        val inflater = LayoutInflater.from(ctx)
        val view = inflater.inflate(R.layout.dialog_jalali_date_picker, null)

        val yearPicker = view.findViewById<NumberPicker>(R.id.jalaliYear)
        val monthPicker = view.findViewById<NumberPicker>(R.id.jalaliMonth)
        val dayPicker = view.findViewById<NumberPicker>(R.id.jalaliDay)

        yearPicker.minValue = j.getYear() - 50
        yearPicker.maxValue = j.getYear() + 50
        yearPicker.value = j.getYear()

        // Use Persian month names in the month picker
        monthPicker.minValue = 1
        monthPicker.maxValue = 12
        monthPicker.value = j.getMonth()
        monthPicker.displayedValues = persianMonthNames

        dayPicker.minValue = 1
        dayPicker.maxValue = getMaxDays(j.getYear(), j.getMonth())
        dayPicker.value = j.getDay()

        // Update days when month or year changes
        val updateDays = {
            val maxDay = getMaxDays(yearPicker.value, monthPicker.value)
            dayPicker.maxValue = maxDay
            if (dayPicker.value > maxDay) dayPicker.value = maxDay
        }
        
        monthPicker.setOnValueChangedListener { _, _, _ -> updateDays() }
        yearPicker.setOnValueChangedListener { _, _, _ -> updateDays() }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("انتخاب تاریخ به شمسی")
            .setView(view)
            .setPositiveButton("انتخاب") { _, _ ->
                val selJ = Result(yearPicker.value, monthPicker.value, dayPicker.value)
                // convert jalali to gregorian via JalaliCalendar round-trip
                val gc = jalaliToGregorian(selJ.year, selJ.month, selJ.day)
                val c = java.util.Calendar.getInstance()
                c.set(gc.get(java.util.Calendar.YEAR), gc.get(java.util.Calendar.MONTH), gc.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
                c.set(java.util.Calendar.MILLISECOND, 0)
                onSelected(c.timeInMillis)
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun jalaliToGregorian(jy: Int, jm: Int, jd: Int): java.util.Calendar {
        return JalaliCalendar.jalaliToGregorian(jy, jm, jd)
    }
}