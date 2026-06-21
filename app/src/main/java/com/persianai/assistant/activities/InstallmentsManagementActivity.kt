package com.persianai.assistant.activities

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import com.persianai.assistant.R
import com.persianai.assistant.adapters.InstallmentsAdapter
import com.persianai.assistant.databinding.ActivityInstallmentsManagementBinding
import com.persianai.assistant.finance.InstallmentManager
import com.persianai.assistant.finance.InstallmentManager.Installment
import com.persianai.assistant.utils.AmountParser
import com.persianai.assistant.utils.PersianDateConverter
import kotlinx.coroutines.launch
import java.util.*

/**
 * ماژول مدیریت جامع اقساط
 * 
 * ✅ ثبت قسط (وام، خرید، اجاره)
 * ✅ تعداد اقساط
 * ✅ جدول زمان‌بندی
 * ✅ هشدارهای هوشمند
 * ✅ محاسبه بدهی باقیمانده
 */
class InstallmentsManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInstallmentsManagementBinding
    private lateinit var installmentsAdapter: InstallmentsAdapter
    private lateinit var installmentManager: InstallmentManager
    private val installments = mutableListOf<Installment>()
    
    private var filterType: FilterType = FilterType.ALL
    
    enum class FilterType {
        ALL,           // همه
        ACTIVE,        // فعال
        COMPLETED,     // تکمیل شده
        OVERDUE,       // عقب افتاده
        UPCOMING       // سررسید نزدیک
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstallmentsManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        initializeManager()
        setupRecyclerView()
        setupListeners()
        loadInstallments()
        updateStats()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.installments_menu, menu)
        return true
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "💳 مدیریت اقساط"
    }
    
    private fun initializeManager() {
        installmentManager = InstallmentManager(this)
    }
    
    private fun setupRecyclerView() {
        installmentsAdapter = InstallmentsAdapter(installments) { installment ->
            viewInstallmentDetails(installment)
        }
        
        binding.installmentsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@InstallmentsManagementActivity)
            adapter = installmentsAdapter
        }
    }
    
    private fun setupListeners() {
        binding.fabAddInstallment.setOnClickListener {
            showAddInstallmentDialog()
        }
        
        // فیلترها بر اساس چیپ‌های موجود در layout
        binding.chipLoan.setOnClickListener { applyFilter(FilterType.ALL) }
        binding.chipPurchase.setOnClickListener { applyFilter(FilterType.ACTIVE) }
        binding.chipRent.setOnClickListener { applyFilter(FilterType.COMPLETED) }
    }
    
    private fun loadInstallments() {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                binding.emptyView.visibility = View.GONE
                
                val allInstallments = installmentManager.getAllInstallments()
                
                installments.clear()
                installments.addAll(allInstallments)
                
                applyFilter(filterType)
                
                binding.progressBar.visibility = View.GONE
                
                if (installments.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.installmentsRecyclerView.visibility = View.GONE
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.installmentsRecyclerView.visibility = View.VISIBLE
                }
                
                updateStats()
                
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this@InstallmentsManagementActivity,
                    "❌ خطا: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun applyFilter(type: FilterType) {
        filterType = type
        
        // Reset chips
        binding.chipLoan.isChecked = false
        binding.chipPurchase.isChecked = false
        binding.chipRent.isChecked = false
        
        val allInstallments = installmentManager.getAllInstallments()
        val today = System.currentTimeMillis()
        val sevenDaysLater = today + (7 * 24 * 60 * 60 * 1000)
        
        val filtered = when (type) {
            FilterType.ALL -> {
                binding.chipLoan.isChecked = true
                allInstallments
            }
            FilterType.ACTIVE -> {
                binding.chipPurchase.isChecked = true
                allInstallments.filter { it.paidInstallments < it.totalInstallments }
            }
            FilterType.COMPLETED -> {
                binding.chipRent.isChecked = true
                allInstallments.filter { it.paidInstallments >= it.totalInstallments }
            }
            FilterType.OVERDUE -> {
                allInstallments.filter { 
                    it.paidInstallments < it.totalInstallments &&
                    hasOverduePayments(it, today)
                }
            }
            FilterType.UPCOMING -> {
                allInstallments.filter {
                    it.paidInstallments < it.totalInstallments &&
                    hasUpcomingPayments(it, today, sevenDaysLater)
                }
            }
        }
        
        installments.clear()
        installments.addAll(filtered)
        installmentsAdapter.notifyDataSetChanged()

        if (installments.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.installmentsRecyclerView.visibility = View.GONE
            binding.emptyView.text = if (installmentManager.getAllInstallments().isEmpty()) {
                "📭 هیچ قسطی ثبت نشده است"
            } else {
                "🔍 قسطی با این فیلتر یافت نشد"
            }
        } else {
            binding.emptyView.visibility = View.GONE
            binding.installmentsRecyclerView.visibility = View.VISIBLE
        }
    }
    
    private fun hasOverduePayments(installment: Installment, now: Long = System.currentTimeMillis()): Boolean {
        val nextPaymentDate = installmentManager.calculateNextPaymentDate(installment) ?: return false
        return nextPaymentDate < now
    }
    
    private fun hasUpcomingPayments(installment: Installment, start: Long, end: Long): Boolean {
        val nextPaymentDate = installmentManager.calculateNextPaymentDate(installment) ?: return false
        return nextPaymentDate in start..end
    }
    
    private fun updateStats() {
        lifecycleScope.launch {
            val allInstallments = installmentManager.getAllInstallments()
            
            if (allInstallments.isEmpty()) {
                binding.statsCard.visibility = View.GONE
                binding.statsText.text = ""
                return@launch
            }
            
            binding.statsCard.visibility = View.VISIBLE
            
            val totalInstallments = allInstallments.size
            val totalAmount = allInstallments.sumOf { it.totalAmount }
            val totalPaid = allInstallments.sumOf { it.paidInstallments * it.installmentAmount }
            val totalRemaining = allInstallments.sumOf { (it.totalInstallments - it.paidInstallments) * it.installmentAmount }
            
            val activeCount = allInstallments.count { it.paidInstallments < it.totalInstallments }
            val completedCount = allInstallments.count { it.paidInstallments >= it.totalInstallments }
            
            val today = System.currentTimeMillis()
            val sevenDaysLater = today + (7 * 24 * 60 * 60 * 1000)
            val overdueCount = allInstallments.count { hasOverduePayments(it, today) }
            val upcomingCount = allInstallments.count { hasUpcomingPayments(it, today, sevenDaysLater) }
            
            val progressPercent = if (totalAmount > 0) {
                ((totalPaid / totalAmount) * 100).toInt()
            } else {
                0
            }
            
            binding.statsText.text = buildString {
                appendLine("تعداد اقساط: $totalInstallments")
                appendLine("مبلغ کل: ${formatAmount(totalAmount)}")
                appendLine("پرداخت شده: ${formatAmount(totalPaid)}")
                appendLine("باقیمانده: ${formatAmount(totalRemaining)}")
                appendLine("پیشرفت: $progressPercent%")
                appendLine("وضعیت: فعال $activeCount | تکمیل $completedCount")
                if (overdueCount > 0 || upcomingCount > 0) {
                    appendLine()
                    if (overdueCount > 0) {
                        appendLine("❌ اقساط عقب افتاده: $overdueCount")
                    }
                    if (upcomingCount > 0) {
                        appendLine("⚠️ اقساط تا ۷ روز آینده: $upcomingCount")
                    }
                }
            }
        }
    }
    
    private fun showAddInstallmentDialog() {
        showInstallmentFormDialog(null)
    }

    private fun showInstallmentFormDialog(existing: Installment?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_installment, null)
        
        val titleInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.titleInput)
        val totalAmountInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.totalAmountInput)
        val installmentAmountInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.installmentAmountInput)
        val totalInstallmentsInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.totalInstallmentsInput)
        val startDateButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.startDateButton)
        val paymentDayInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.paymentDayInput)
        val recipientInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.recipientInput)
        val descriptionInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.descriptionInput)
        
        var selectedStartDate: Long = existing?.startDate ?: System.currentTimeMillis()

        // set initial Persian date display even when creating new installment
        run {
            val c = Calendar.getInstance().apply { timeInMillis = selectedStartDate }
            val persianDate = PersianDateConverter.gregorianToPersian(
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
            )
            startDateButton.text = persianDate.toReadableString()
        }

        existing?.let { inst ->
            titleInput.setText(inst.title)
            totalAmountInput.setText(inst.totalAmount.toLong().toString())
            installmentAmountInput.setText(inst.installmentAmount.toLong().toString())
            totalInstallmentsInput.setText(inst.totalInstallments.toString())
            paymentDayInput.setText(inst.paymentDay.toString())
            recipientInput.setText(inst.recipient)
            descriptionInput.setText(inst.description)
            val calendar = Calendar.getInstance().apply { timeInMillis = inst.startDate }
            val persianDate = PersianDateConverter.gregorianToPersian(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            startDateButton.text = persianDate.toReadableString()
        }
        
        startDateButton.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("تاریخ شروع")
                .setSelection(selectedStartDate)
                .build()
            
                datePicker.addOnPositiveButtonClickListener { selection ->
                    // normalize selection to local midnight to avoid timezone shifts
                    val c = Calendar.getInstance().apply { timeInMillis = selection }
                    c.set(Calendar.HOUR_OF_DAY, 0)
                    c.set(Calendar.MINUTE, 0)
                    c.set(Calendar.SECOND, 0)
                    c.set(Calendar.MILLISECOND, 0)
                    selectedStartDate = c.timeInMillis

                    val persianDate = PersianDateConverter.gregorianToPersian(
                        c.get(Calendar.YEAR),
                        c.get(Calendar.MONTH) + 1,
                        c.get(Calendar.DAY_OF_MONTH)
                    )
                    startDateButton.text = persianDate.toReadableString()
                }
            
            datePicker.show(supportFragmentManager, "DATE_PICKER")
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "➕ افزودن قسط جدید" else "✏️ ویرایش قسط")
            .setView(dialogView)
            .setPositiveButton("ذخیره") { _, _ ->
                val title = titleInput.text?.toString()?.trim().orEmpty()
                val totalAmount = AmountParser.parse(totalAmountInput.text?.toString())
                val totalInstallments = totalInstallmentsInput.text?.toString()?.toIntOrNull() ?: 0
                val manualInstallmentAmount = AmountParser.parse(installmentAmountInput.text?.toString()).takeIf { it > 0 }
                val paymentDay = paymentDayInput.text?.toString()?.toIntOrNull() ?: -1
                val recipient = recipientInput.text?.toString()?.trim().orEmpty()
                val description = descriptionInput.text?.toString()?.trim().orEmpty()
                
                if (title.isEmpty()) {
                    Toast.makeText(this, "⚠️ عنوان را وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                if (totalAmount <= 0) {
                    Toast.makeText(this, "⚠️ مبلغ کل را وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (totalInstallments <= 0) {
                    Toast.makeText(this, "⚠️ تعداد اقساط را وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                if (paymentDay !in 1..31) {
                    Toast.makeText(this, "⚠️ روز پرداخت را بین ۱ تا ۳۱ وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (existing != null && totalInstallments < existing.paidInstallments) {
                    Toast.makeText(
                        this,
                        "⚠️ تعداد اقساط نمی‌تواند کمتر از ${existing.paidInstallments} (پرداخت‌شده) باشد",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                
                val installmentAmount = manualInstallmentAmount
                    ?: (totalAmount / totalInstallments).takeIf { it > 0 }
                    ?: 0.0
                
                if (installmentAmount <= 0) {
                    Toast.makeText(this, "⚠️ مبلغ هر قسط را وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (existing == null) {
                    addInstallment(
                        title = title,
                        totalAmount = totalAmount,
                        installmentAmount = installmentAmount,
                        totalInstallments = totalInstallments,
                        startDate = selectedStartDate,
                        paymentDay = paymentDay,
                        creditor = recipient,
                        notes = description
                    )
                } else {
                    val success = installmentManager.updateInstallment(
                        id = existing.id,
                        title = title,
                        totalAmount = totalAmount,
                        installmentAmount = installmentAmount,
                        totalInstallments = totalInstallments,
                        startDate = selectedStartDate,
                        paymentDay = paymentDay,
                        recipient = recipient,
                        description = description
                    )
                    if (success) {
                        Toast.makeText(this, "✅ قسط ویرایش شد", Toast.LENGTH_SHORT).show()
                        loadInstallments()
                    } else {
                        Toast.makeText(this, "❌ خطا در ویرایش قسط", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }
    
    private fun addInstallment(
        title: String,
        totalAmount: Double,
        installmentAmount: Double,
        totalInstallments: Int,
        startDate: Long,
        paymentDay: Int,
        creditor: String,
        notes: String
    ) {
        lifecycleScope.launch {
            try {
                // ثبت هشدارها برای پرداخت‌ها
                installmentManager.addInstallment(
                    title = title,
                    totalAmount = totalAmount,
                    installmentAmount = installmentAmount,
                    totalInstallments = totalInstallments,
                    startDate = startDate,
                    paymentDay = paymentDay,
                    recipient = creditor,
                    description = notes
                )
                
                Toast.makeText(
                    this@InstallmentsManagementActivity,
                    "✅ قسط با موفقیت ثبت شد",
                    Toast.LENGTH_SHORT
                ).show()
                
                loadInstallments()
                
            } catch (e: Exception) {
                Toast.makeText(
                    this@InstallmentsManagementActivity,
                    "❌ خطا: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun viewInstallmentDetails(installment: Installment) {
        val statusText = if (installment.paidInstallments >= installment.totalInstallments) {
            "✔️ تکمیل"
        } else {
            "✅ فعال"
        }
        
        val remainingInstallments = installment.totalInstallments - installment.paidInstallments
        val remainingAmount = remainingInstallments * installment.installmentAmount
        val progressPercent = if (installment.totalInstallments > 0) {
            ((installment.paidInstallments.toDouble() / installment.totalInstallments) * 100).toInt()
        } else {
            0
        }
        
        val details = buildString {
            appendLine("عنوان: ${installment.title}")
            appendLine("مبلغ کل: ${formatAmount(installment.totalAmount)}")
            appendLine("مبلغ هر قسط: ${formatAmount(installment.installmentAmount)}")
            appendLine("تعداد اقساط: ${installment.totalInstallments}")
            appendLine("پرداخت شده: ${installment.paidInstallments} قسط")
            appendLine("باقیمانده: ${remainingInstallments} قسط")
            appendLine("مبلغ باقیمانده: ${formatAmount(remainingAmount)}")
            appendLine("پیشرفت: $progressPercent%")
            appendLine("وضعیت: $statusText")
            if (installment.recipient.isNotEmpty()) {
                appendLine("طلبکار: ${installment.recipient}")
            }
            if (installment.description.isNotEmpty()) {
                appendLine("\nیادداشت:")
                appendLine(installment.description)
            }
        }
        
        // Use custom dialog layout so all three action buttons are visible and large
        val dialogView = layoutInflater.inflate(R.layout.dialog_installment_details, null)

        val titleTv = dialogView.findViewById<TextView>(R.id.titleText)
        val totalAmountTv = dialogView.findViewById<TextView>(R.id.totalAmountText)
        val monthlyAmountTv = dialogView.findViewById<TextView>(R.id.monthlyAmountText)
        val progressTv = dialogView.findViewById<TextView>(R.id.progressText)
        val remainingTv = dialogView.findViewById<TextView>(R.id.remainingText)
        val nextPaymentTv = dialogView.findViewById<TextView>(R.id.nextPaymentText)
        val recipientTv = dialogView.findViewById<TextView>(R.id.recipientText)
        val notesTv = dialogView.findViewById<TextView>(R.id.notesText)

        titleTv.text = installment.title
        totalAmountTv.text = "مبلغ کل: ${formatAmount(installment.totalAmount)}"
        monthlyAmountTv.text = "مبلغ هر قسط: ${formatAmount(installment.installmentAmount)}"
        progressTv.text = "پرداخت: ${installment.paidInstallments} از ${installment.totalInstallments}"
        val remainingInstallments = installment.totalInstallments - installment.paidInstallments
        val remainingAmount = remainingInstallments * installment.installmentAmount
        remainingTv.text = "باقیمانده: ${formatAmount(remainingAmount)}"
        recipientTv.text = if (installment.recipient.isNotBlank()) "طلبکار: ${installment.recipient}" else ""
        notesTv.text = if (installment.description.isNotBlank()) "توضیحات:\n${installment.description}" else ""

        // calculate next payment and show Persian date + days remaining
        val nextPayment = installmentManager.calculateNextPaymentDate(installment)
        if (nextPayment != null) {
            val days = ((nextPayment - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
            val cal = Calendar.getInstance().apply { timeInMillis = nextPayment }
            val pers = PersianDateConverter.gregorianToPersian(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)).toReadableString()
            nextPaymentTv.text = "قسط بعدی: $days روز دیگر ($pers)"
        } else {
            nextPaymentTv.text = "قسط بعدی: -"
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        // wire buttons
        val scheduleBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.scheduleButton)
        val actionsBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.actionsButton)
        val closeBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.closeButton)

        scheduleBtn.setOnClickListener {
            dialog.dismiss()
            viewPaymentSchedule(installment)
        }

        actionsBtn.setOnClickListener {
            dialog.dismiss()
            showInstallmentActions(installment)
        }

        closeBtn.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showInstallmentActions(installment: Installment) {
        val actions = mutableListOf("✏️ ویرایش", "🗑️ حذف")
        if (installment.paidInstallments < installment.totalInstallments) {
            actions.add(0, "✅ ثبت پرداخت")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("عملیات")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "✅ ثبت پرداخت" -> markPaymentPaid(installment)
                    "✏️ ویرایش" -> editInstallment(installment)
                    "🗑️ حذف" -> deleteInstallment(installment)
                }
            }
            .show()
    }
    
    private fun viewPaymentSchedule(installment: Installment) {
        val schedule = buildString {
            appendLine("📅 جدول زمان‌بندی پرداخت")
            appendLine("================")
            appendLine()
            
            val now = System.currentTimeMillis()
            val calendar = Calendar.getInstance()
            
            for (i in 1..installment.totalInstallments) {
                calendar.timeInMillis = installment.startDate
                calendar.add(Calendar.MONTH, i - 1)
                calendar.set(Calendar.DAY_OF_MONTH, installment.paymentDay)
                val dueTime = calendar.timeInMillis
                val dueCal = Calendar.getInstance().apply {
                    timeInMillis = dueTime
                }
                val persianDate = PersianDateConverter.gregorianToPersian(
                    dueCal.get(Calendar.YEAR),
                    dueCal.get(Calendar.MONTH) + 1,
                    dueCal.get(Calendar.DAY_OF_MONTH)
                )
                val isPaid = i <= installment.paidInstallments
                val status = when {
                    isPaid -> "✅ پرداخت شده"
                    dueTime < now -> "❌ عقب افتاده"
                    else -> "⏳ در انتظار"
                }
                
                appendLine("قسط ${i}:")
                appendLine("  مبلغ: ${formatAmount(installment.installmentAmount)}")
                appendLine("  سررسید: ${persianDate.toReadableString()}")
                appendLine("  وضعیت: $status")
                appendLine()
            }
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("جدول پرداخت")
            .setMessage(schedule)
            .setPositiveButton("بستن", null)
            .show()
    }
    
    private fun markPaymentPaid(installment: Installment) {
        if (installment.paidInstallments >= installment.totalInstallments) {
            Toast.makeText(this, "✅ همه اقساط پرداخت شده‌اند", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("ثبت پرداخت قسط")
            .setMessage("قسط ${installment.paidInstallments + 1} از ${installment.totalInstallments} به مبلغ ${formatAmount(installment.installmentAmount)} پرداخت شود؟\n\nاین مبلغ به هزینه‌ها اضافه می‌شود.")
            .setPositiveButton("ثبت پرداخت") { _, _ ->
                lifecycleScope.launch {
                    try {
                        installmentManager.payInstallment(installment.id)
                        Toast.makeText(
                            this@InstallmentsManagementActivity,
                            "✅ پرداخت قسط ثبت شد",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadInstallments()
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@InstallmentsManagementActivity,
                            "❌ خطا: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }
    
    private fun editInstallment(installment: Installment) {
        showInstallmentFormDialog(installment)
    }
    
    private fun deleteInstallment(installment: Installment) {
        MaterialAlertDialogBuilder(this)
            .setTitle("حذف قسط")
            .setMessage("آیا از حذف این قسط مطمئن هستید؟\n\nتمام اطلاعات پرداخت‌ها نیز حذف خواهند شد.")
            .setPositiveButton("بله") { _, _ ->
                lifecycleScope.launch {
                    try {
                        installmentManager.deleteInstallment(installment.id)
                        
                        Toast.makeText(
                            this@InstallmentsManagementActivity,
                            "✅ قسط حذف شد",
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        loadInstallments()
                        
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@InstallmentsManagementActivity,
                            "❌ خطا: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("خیر", null)
            .show()
    }
    
    private fun formatAmount(amount: Double): String {
        return String.format("%,.0f تومان", amount)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_report -> {
                generateReport()
                true
            }
            R.id.action_export -> {
                exportInstallments()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun exportInstallments() {
        val csv = installmentManager.exportToCSV()
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "گزارش اقساط Maliar")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(shareIntent, "اشتراک‌گذاری گزارش اقساط"))
    }
    
    private fun generateReport() {
        MaterialAlertDialogBuilder(this)
            .setTitle("📊 گزارش اقساط")
            .setMessage(installmentManager.generateReport())
            .setPositiveButton("بستن", null)
            .show()
    }
}