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
 */
class JalaliDatePickerDialog(private val ctx: Context) {

    data class Result(val year: Int, val month: Int, val day: Int)

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

        monthPicker.minValue = 1
        monthPicker.maxValue = 12
        monthPicker.value = j.getMonth()

        dayPicker.minValue = 1
        dayPicker.maxValue = 31
        dayPicker.value = j.getDay()

        MaterialAlertDialogBuilder(ctx)
            .setTitle("انتخاب تاریخ")
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
