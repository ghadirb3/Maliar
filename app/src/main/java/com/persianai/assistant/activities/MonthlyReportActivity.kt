package com.persianai.assistant.activities

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.android.material.button.MaterialButton
import com.persianai.assistant.R
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.data.Transaction
import com.persianai.assistant.data.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * داشبورد گزارش ماهیانه با نمودارهای MPAndroidChart
 * نمایش درآمد/هزینه به صورت نمودار میله‌ای و دایره‌ای
 */
class MonthlyReportActivity : AppCompatActivity() {

    private lateinit var db: AccountingDB
    private lateinit var txtTotalIncome: TextView
    private lateinit var txtTotalExpense: TextView
    private lateinit var txtNetBalance: TextView
    private lateinit var txtPeriod: TextView
    private lateinit var barChart: BarChart
    private lateinit var pieChart: PieChart
    private lateinit var btnPrevMonth: MaterialButton
    private lateinit var btnNextMonth: MaterialButton

    private var currentYear: Int = 0
    private var currentMonth: Int = 0

    companion object {
        private val PERSIAN_MONTHS = arrayOf(
            "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
        )
        private val COLORS = intArrayOf(
            Color.rgb(76, 175, 80),   // Green - Income
            Color.rgb(244, 67, 54),   // Red - Expense
            Color.rgb(33, 150, 243),  // Blue
            Color.rgb(255, 152, 0),   // Orange
            Color.rgb(156, 39, 176),  // Purple
            Color.rgb(0, 188, 212),   // Cyan
            Color.rgb(233, 30, 99),   // Pink
            Color.rgb(121, 85, 72)    // Brown
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monthly_report)

        db = AccountingDB(this)

        // Setup toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "گزارش ماهیانه"

        // Initialize views
        txtTotalIncome = findViewById(R.id.txtTotalIncome)
        txtTotalExpense = findViewById(R.id.txtTotalExpense)
        txtNetBalance = findViewById(R.id.txtNetBalance)
        txtPeriod = findViewById(R.id.txtPeriod)
        barChart = findViewById(R.id.barChart)
        pieChart = findViewById(R.id.pieChart)
        btnPrevMonth = findViewById(R.id.btnPrevMonth)
        btnNextMonth = findViewById(R.id.btnNextMonth)

        // Initialize to current Persian month
        val now = Calendar.getInstance()
        val persianCal = java.util.Calendar.getInstance()
        currentYear = persianCal.get(Calendar.YEAR)
        currentMonth = persianCal.get(Calendar.MONTH)

        updatePeriodText()

        btnPrevMonth.setOnClickListener {
            currentMonth--
            if (currentMonth < 0) {
                currentMonth = 11
                currentYear--
            }
            updatePeriodText()
            loadMonthlyData()
        }

        btnNextMonth.setOnClickListener {
            currentMonth++
            if (currentMonth > 11) {
                currentMonth = 0
                currentYear++
            }
            updatePeriodText()
            loadMonthlyData()
        }

        // Setup charts
        setupBarChart()
        setupPieChart()

        // Load initial data
        loadMonthlyData()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun updatePeriodText() {
        txtPeriod.text = "${PERSIAN_MONTHS[currentMonth]} $currentYear"
    }

    private fun setupBarChart() {
        barChart.apply {
            description.isEnabled = false
            setFitBars(true)
            setDrawGridBackground(false)
            setPinchZoom(false)
            setScaleEnabled(false)

            val xAxis = xAxis
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.granularity = 1f
            xAxis.textSize = 10f

            axisLeft.setDrawGridLines(true)
            axisLeft.setDrawAxisLine(false)
            axisLeft.textSize = 10f
            axisRight.isEnabled = false

            legend.textSize = 12f
            animateY(800)
        }
    }

    private fun setupPieChart() {
        pieChart.apply {
            description.isEnabled = false
            setUsePercentValues(true)
            setDrawEntryLabels(true)
            setEntryLabelTextSize(10f)
            setEntryLabelColor(Color.BLACK)
            isDrawHoleEnabled = true
            holeRadius = 40f
            setTransparentCircleRadius(45f)
            legend.textSize = 11f
            legend.isWordWrapEnabled = true
            animateY(800)
        }
    }

    private fun loadMonthlyData() {
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                generateMonthlyReport(currentYear, currentMonth + 1)
            }
            updateUI(report)
        }
    }

    private fun generateMonthlyReport(year: Int, month: Int): MonthlyReport {
        val calendar = Calendar.getInstance()
        calendar.set(year, month - 1, 1, 0, 0, 0)
        val startTime = calendar.timeInMillis

        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        val endTime = calendar.timeInMillis

        val allTransactions = db.getAllTransactions()
        val monthTransactions = allTransactions.filter { it.date in startTime..endTime }

        var totalIncome = 0.0
        var totalExpense = 0.0
        val incomeByCategory = mutableMapOf<String, Double>()
        val expenseByCategory = mutableMapOf<String, Double>()

        // Daily breakdown for bar chart (30 days)
        val dailyIncome = DoubleArray(31)
        val dailyExpense = DoubleArray(31)

        monthTransactions.forEach { t ->
            val day = Calendar.getInstance().apply { timeInMillis = t.date }.get(Calendar.DAY_OF_MONTH)

            when (t.type) {
                TransactionType.INCOME -> {
                    totalIncome += t.amount
                    incomeByCategory[t.category] = (incomeByCategory[t.category] ?: 0.0) + t.amount
                    if (day in 1..31) dailyIncome[day - 1] += t.amount
                }
                TransactionType.EXPENSE -> {
                    totalExpense += t.amount
                    expenseByCategory[t.category] = (expenseByCategory[t.category] ?: 0.0) + t.amount
                    if (day in 1..31) dailyExpense[day - 1] += t.amount
                }
                else -> {}
            }
        }

        return MonthlyReport(
            year = year,
            month = month,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            netBalance = totalIncome - totalExpense,
            incomeByCategory = incomeByCategory,
            expenseByCategory = expenseByCategory,
            dailyIncome = dailyIncome.toList(),
            dailyExpense = dailyExpense.toList()
        )
    }

    private fun updateUI(report: MonthlyReport) {
        val formatter = NumberFormat.getNumberInstance(Locale.US)

        txtTotalIncome.text = "${formatter.format(report.totalIncome.roundToInt())} تومان"
        txtTotalExpense.text = "${formatter.format(report.totalExpense.roundToInt())} تومان"

        val netColor = if (report.netBalance >= 0) Color.rgb(76, 175, 80) else Color.rgb(244, 67, 54)
        txtNetBalance.text = "${formatter.format(report.netBalance.roundToInt())} تومان"
        txtNetBalance.setTextColor(netColor)

        updateBarChart(report)
        updatePieChart(report)
    }

    private fun updateBarChart(report: MonthlyReport) {
        val entries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()

        val daysInMonth = when (report.month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (report.year % 4 == 0) 29 else 28
            else -> 30
        }

        // Group by weeks for cleaner display
        val weekIncome = DoubleArray(5)
        val weekExpense = DoubleArray(5)
        val weekLabels = listOf("هفته 1", "هفته 2", "هفته 3", "هفته 4", "هفته 5")

        for (day in 0 until daysInMonth) {
            val weekIndex = day / 7
            if (weekIndex < 5) {
                weekIncome[weekIndex] += report.dailyIncome.getOrElse(day) { 0.0 }
                weekExpense[weekIndex] += report.dailyExpense.getOrElse(day) { 0.0 }
            }
        }

        for (i in 0 until 5) {
            entries.add(BarEntry(i.toFloat(), floatArrayOf(weekIncome[i].toFloat(), weekExpense[i].toFloat())))
            labels.add(weekLabels.getOrElse(i) { "" })
        }

        val set = BarDataSet(entries, "").apply {
            stackLabels = listOf("درآمد", "هزینه")
            colors = listOf(Color.rgb(76, 175, 80), Color.rgb(244, 67, 54))
            valueTextSize = 10f
            setDrawValues(false)
        }

        barChart.data = BarData(set)
        barChart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        barChart.xAxis.setLabelCount(5, false)
        barChart.invalidate()
    }

    private fun updatePieChart(report: MonthlyReport) {
        val entries = mutableListOf<PieEntry>()

        // Top 5 expense categories
        val sortedExpenses = report.expenseByCategory.entries
            .sortedByDescending { it.value }
            .take(5)

        sortedExpenses.forEach { (category, amount) ->
            entries.add(PieEntry(amount.toFloat(), category.ifBlank { "متفرقه" }))
        }

        if (entries.isEmpty()) {
            entries.add(PieEntry(1f, "هزینه‌ای ثبت نشده"))
        }

        val colors = mutableListOf<Int>()
        for (i in entries.indices) {
            colors.add(COLORS[i % COLORS.size])
        }

        val dataSet = PieDataSet(entries, "دسته‌بندی هزینه‌ها").apply {
            this.colors = colors
            valueTextSize = 11f
            valueTextColor = Color.WHITE
            sliceSpace = 2f
            selectionShift = 5f
        }

        pieChart.data = PieData(dataSet)
        pieChart.centerText = "هزینه‌ها\n${NumberFormat.getNumberInstance(Locale.US).format(report.totalExpense.roundToInt())} تومان"
        pieChart.invalidate()
    }

    data class MonthlyReport(
        val year: Int,
        val month: Int,
        val totalIncome: Double,
        val totalExpense: Double,
        val netBalance: Double,
        val incomeByCategory: Map<String, Double>,
        val expenseByCategory: Map<String, Double>,
        val dailyIncome: List<Double>,
        val dailyExpense: List<Double>
    )
}