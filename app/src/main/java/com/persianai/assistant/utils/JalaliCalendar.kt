package com.persianai.assistant.utils

import java.util.Calendar
import java.util.GregorianCalendar

class JalaliCalendar(year: Int, month: Int, day: Int) {

    private var year: Int = 0
    private var month: Int = 0
    private var day: Int = 0

    init {
        set(year, month, day)
    }

    constructor() : this(0, 0, 0) {
        val calendar = GregorianCalendar()
        val gYear = calendar.get(Calendar.YEAR)
        val gMonth = calendar.get(Calendar.MONTH) + 1
        val gDay = calendar.get(Calendar.DAY_OF_MONTH)
        val j = Companion.gregorianToJalali(gYear, gMonth, gDay)
        set(j.year, j.month, j.day)
    }

    fun getYear(): Int = year
    fun getMonth(): Int = month
    fun getDay(): Int = day

    fun set(year: Int, month: Int, day: Int) {
        this.year = year
        this.month = month
        this.day = day
    }

    override fun toString(): String {
        return String.format("%04d/%02d/%02d", getYear(), getMonth(), getDay())
    }
    

    companion object {
        private val jDaysInMonth = intArrayOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29)
        private val gDaysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

        private fun isGregorianLeap(y: Int): Boolean {
            return (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)
        }

        /**
         * Convert Gregorian date to a JalaliCalendar instance.
         */
        fun gregorianToJalali(gYear: Int, gMonth: Int, gDay: Int): JalaliCalendar {
            val gDays = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            val jDays = intArrayOf(0, 31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29)

            var gy = gYear - 1600
            var gm = gMonth - 1
            var gd = gDay - 1

            var gDayNo = 365 * gy + (gy + 3) / 4 - (gy + 99) / 100 + (gy + 399) / 400

            for (i in 0 until gm) {
                gDayNo += gDays[i + 1]
            }
            if (gm > 1 && (gYear % 4 == 0 && gYear % 100 != 0 || gYear % 400 == 0)) {
                gDayNo++
            }
            gDayNo += gd

            var jDayNo = gDayNo - 79

            val jNp = jDayNo / 12053
            jDayNo %= 12053

            var jy = 979 + 33 * jNp + 4 * (jDayNo / 1461)
            jDayNo %= 1461

            if (jDayNo >= 366) {
                jy += (jDayNo - 1) / 365
                jDayNo = (jDayNo - 1) % 365
            }

            var i = 0
            while (i < 11 && jDayNo >= jDays[i + 1]) {
                jDayNo -= jDays[i + 1]
                i++
            }
            val jm = i + 1
            val jd = jDayNo + 1

            return JalaliCalendar(jy, jm, jd)
        }

        /**
         * Check if a Jalali year is a leap year
         * Jalali leap years follow a 33-year cycle: years 1, 5, 9, 13, 17, 22, 26, 30 in each cycle
         */
        fun isJalaliLeapYear(jy: Int): Boolean {
            val yearInCycle = jy % 33
            val leapYearsInCycle = setOf(1, 5, 9, 13, 17, 22, 26, 30)
            return yearInCycle in leapYearsInCycle
        }

        fun jalaliToGregorian(jy: Int, jm: Int, jd: Int): java.util.Calendar {
            var jy2 = jy - 979
            val jm2 = jm - 1
            val jd2 = jd - 1

            var jDayNo = 365 * jy2 + (jy2 / 33) * 8 + ((jy2 % 33 + 3) / 4)
            for (i in 0 until jm2) {
                jDayNo += jDaysInMonth[i]
            }
            jDayNo += jd2

            var gDayNo = jDayNo + 79

            var gy = 1600 + 400 * (gDayNo / 146097)
            gDayNo %= 146097

            if (gDayNo >= 36525) {
                gDayNo -= 1
                gy += 100 * (gDayNo / 36524)
                gDayNo %= 36524
                if (gDayNo >= 365) {
                    gDayNo += 1
                }
            }

            gy += 4 * (gDayNo / 1461)
            gDayNo %= 1461

            if (gDayNo >= 366) {
                gy += (gDayNo - 1) / 365
                gDayNo = (gDayNo - 1) % 365
            }

            var i = 0
            while (i < 12) {
                var v = gDaysInMonth[i]
                if (i == 1 && isGregorianLeap(gy)) v = 29
                if (gDayNo < v) break
                gDayNo -= v
                i++
            }

            val gm = i + 1
            val gd = gDayNo + 1

            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.YEAR, gy)
            cal.set(java.util.Calendar.MONTH, gm - 1)
            cal.set(java.util.Calendar.DAY_OF_MONTH, gd)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)

            return cal
        }
    }
}
