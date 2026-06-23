package com.persianai.assistant.activities

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.persianai.assistant.R
import com.persianai.assistant.adapters.FinancialTransactionAdapter
import com.persianai.assistant.adapters.CheckAdapter
import com.persianai.assistant.adapters.InstallmentAdapter
import com.persianai.assistant.databinding.ActivityProfessionalAccountingBinding
import com.persianai.assistant.models.*
import com.persianai.assistant.utils.PersianDateConverter
import com.persianai.assistant.utils.AccountingManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * اکتیویتی حسابداری حرفه‌ای با مدیریت کامل تراکنش‌ها، چک‌ها و اقساط
 */
class ProfessionalAccountingActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityProfessionalAccountingBinding
    private lateinit var accountingManager: AccountingManager
    private lateinit var transactionAdapter: FinancialTransactionAdapter
    private lateinit var checkAdapter: CheckAdapter
    private lateinit var installmentAdapter: InstallmentAdapter
    
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale("fa", "IR"))
    private val calendar = Calendar.getInstance()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityProfessionalAccountingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        accountingManager = AccountingManager(this)
        
        setupToolbar()
        setupTabs()
        setupRecyclerViews()
        setupClickListeners()
        loadFinancialData()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = "حسابداری حرفه‌ای"
        }
    }
    
    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showTransactions()
                    1 -> showChecks()
                    2 -> showInstallments()
                    3 -> showReports()
                }
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }
    
    private fun setupRecyclerViews() {
        // تنظیم RecyclerView برای تراکنش‌ها
        transactionAdapter = FinancialTransactionAdapter { transaction ->
            showTransactionDialog(transaction)
        }
        binding.recyclerViewTransactions.apply {
            layoutManager = LinearLayoutManager(this@ProfessionalAccountingActivity)
            adapter = transactionAdapter
        }
        
        // تنظیم RecyclerView برای چک‌ها
        checkAdapter = CheckAdapter(
            onCheckClick = { check ->
                showCheckDialog(check)
            }
        )
        binding.recyclerViewChecks.apply {
            layoutManager = LinearLayoutManager(this@ProfessionalAccountingActivity)
            adapter = checkAdapter
        }
        
        // تنظیم RecyclerView برای اقساط
        installmentAdapter = InstallmentAdapter(
            onInstallmentClick = { installment ->
                showInstallmentDialog(installment)
            }
        )
        binding.recyclerViewInstallments.apply {
            layoutManager = LinearLayoutManager(this@ProfessionalAccountingActivity)
            adapter = installmentAdapter
        }
    }
    
    private fun setupClickListeners() {
        binding.fabAddTransaction.setOnClickListener {
            showAddTransactionDialog()
        }
        
        binding.fabAddCheck.setOnClickListener {
            showAddCheckDialog()
        }
        
        binding.fabAddInstallment.setOnClickListener {
            showAddInstallmentDialog()
        }
    }
    
    private fun loadFinancialData() {
        lifecycleScope.launch {
            try {
                // بارگذاری تراکنش‌ها
                val transactions = accountingManager.getAllTransactions()
                transactionAdapter.submitList(transactions)
                
                // بارگذاری چک‌ها
                val checks = accountingManager.getAllChecks()
                checkAdapter.submitList(checks)
                
                // بارگذاری اقساط
                val installments = accountingManager.getAllInstallments()
                installmentAdapter.submitList(installments)
                
                // به‌روزرسانی آمار
                updateStatistics()
                
            } catch (e: Exception) {
                Toast.makeText(this@ProfessionalAccountingActivity, "خطا در بارگذاری داده‌ها", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateStatistics() {
        lifecycleScope.launch {
            try {
                val stats = accountingManager.getFinancialStatistics()
                
                binding.totalIncomeText.text = "${String.format("%,.0f", stats.totalIncome)} تومان"
                binding.totalExpenseText.text = "${String.format("%,.0f", stats.totalExpense)} تومان"
                binding.balanceText.text = "${String.format("%,.0f", stats.balance)} تومان"
                
                // تنظیم رنگ تراز
                binding.balanceText.setTextColor(
                    when {
                        stats.balance > 0 -> getColor(R.color.income_green)
                        stats.balance < 0 -> getColor(R.color.expense_red)
                        else -> getColor(R.color.neutral_gray)
                    }
                )
                
            } catch (e: Exception) {
                Toast.makeText(this@ProfessionalAccountingActivity, "خطا در به‌روزرسانی آمار", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showTransactions() {
        binding.recyclerViewTransactions.visibility = android.view.View.VISIBLE
        binding.recyclerViewChecks.visibility = android.view.View.GONE
        binding.recyclerViewInstallments.visibility = android.view.View.GONE
        binding.reportsLayout.visibility = android.view.View.GONE
        binding.fabAddTransaction.show()
        binding.fabAddCheck.hide()
        binding.fabAddInstallment.hide()
    }
    
    private fun showChecks() {
        binding.recyclerViewTransactions.visibility = android.view.View.GONE
        binding.recyclerViewChecks.visibility = android.view.View.VISIBLE
        binding.recyclerViewInstallments.visibility = android.view.View.GONE
        binding.reportsLayout.visibility = android.view.View.GONE
        binding.fabAddTransaction.hide()
        binding.fabAddCheck.show()
        binding.fabAddInstallment.hide()
    }
    
    private fun showInstallments() {
        binding.recyclerViewTransactions.visibility = android.view.View.GONE
        binding.recyclerViewChecks.visibility = android.view.View.GONE
        binding.recyclerViewInstallments.visibility = android.view.View.VISIBLE
        binding.reportsLayout.visibility = android.view.View.GONE
        binding.fabAddTransaction.hide()
        binding.fabAddCheck.hide()
        binding.fabAddInstallment.show()
    }
    
    private fun showReports() {
        binding.recyclerViewTransactions.visibility = android.view.View.GONE
        binding.recyclerViewChecks.visibility = android.view.View.GONE
        binding.recyclerViewInstallments.visibility = android.view.View.GONE
        binding.reportsLayout.visibility = android.view.View.VISIBLE
        binding.fabAddTransaction.hide()
        binding.fabAddCheck.hide()
        binding.fabAddInstallment.hide()
        
        loadReports()
    }
    
    private fun loadReports() {
        lifecycleScope.launch {
            try {
                val monthlyReport = accountingManager.getMonthlyReport()
                val yearlyReport = accountingManager.getYearlyReport()
                
                // نمایش گزارش ماهانه
                binding.monthlyIncomeText.text = "${String.format("%,.0f", monthlyReport.totalIncome)} تومان"
                binding.monthlyExpenseText.text = "${String.format("%,.0f", monthlyReport.totalExpense)} تومان"
                binding.monthlyBalanceText.text = "${String.format("%,.0f", monthlyReport.netIncome)} تومان"
                
                // نمایش گزارش سالانه
                binding.yearlyIncomeText.text = "${String.format("%,.0f", yearlyReport.totalIncome)} تومان"
                binding.yearlyExpenseText.text = "${String.format("%,.0f", yearlyReport.totalExpense)} تومان"
                binding.yearlyBalanceText.text = "${String.format("%,.0f", yearlyReport.netIncome)} تومان"
                
            } catch (e: Exception) {
                Toast.makeText(this@ProfessionalAccountingActivity, "خطا در بارگذاری گزارش‌ها", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showAddTransactionDialog() {
        // دیالوگ افزودن تراکنش جدید
        val options = arrayOf("درآمد", "هزینه")
        MaterialAlertDialogBuilder(this)
            .setTitle("نوع تراکنش")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showIncomeDialog()
                    1 -> showExpenseDialog()
                }
            }
            .show()
    }
    
    private fun showIncomeDialog() {
        // پیاده‌سازی دیالوگ درآمد
        Toast.makeText(this, "در حال توسعه دیالوگ درآمد", Toast.LENGTH_SHORT).show()
    }
    
    private fun showExpenseDialog() {
        // پیاده‌سازی دیالوگ هزینه
        Toast.makeText(this, "در حال توسعه دیالوگ هزینه", Toast.LENGTH_SHORT).show()
    }
    
    private fun showAddCheckDialog() {
        // پیاده‌سازی دیالوگ افزودن چک
        Toast.makeText(this, "در حال توسعه دیالوگ چک", Toast.LENGTH_SHORT).show()
    }
    
    private fun showAddInstallmentDialog() {
        // پیاده‌سازی دیالوگ افزودن قسط
        Toast.makeText(this, "در حال توسعه دیالوگ قسط", Toast.LENGTH_SHORT).show()
    }
    
    private fun showTransactionDialog(transaction: FinancialTransaction) {
        // نمایش جزئیات تراکنش
        Toast.makeText(this, "جزئیات تراکنش: ${transaction.description}", Toast.LENGTH_SHORT).show()
    }
    
    private fun showCheckDialog(check: Check) {
        // نمایش و امکان ویرایش چک
        val view = layoutInflater.inflate(R.layout.dialog_add_check, null)
        val checkNumberInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.checkNumberInput)
        val amountInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.amountInput)
        val issuerInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.issuerInput)
        val recipientInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.recipientInput)
        val issueDateButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.issueDateButton)
        val dueDateButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.dueDateButton)
        val bankNameInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.bankNameInput)
        val accountNumberInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.accountNumberInput)
        val descriptionInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.descriptionInput)
        val receivedCheckbox = view.findViewById<android.widget.CheckBox>(R.id.receivedCheckbox)

        // پیش‌پر کردن مقادیر (استفاده از مدل `models.Check`)
        checkNumberInput.setText(check.checkNumber)
        amountInput.setText(check.amount.toString())
        // مدل `Check` در این بخش فیلد `recipient` را نگهداری می‌کند؛
        // برای جلوگیری از خطاهای کامپایل از فیلدهای موجود استفاده می‌کنیم.
        issuerInput.setText(check.recipient)
        recipientInput.setText(check.recipient)
        bankNameInput.setText(check.bankName ?: "")
        // در مدل جدید فیلد شماره حساب به نام `accountId` آمده است
        accountNumberInput.setText(check.accountId ?: "")
        descriptionInput.setText(check.description ?: "")
        // فیلد `isIncoming` در مدل `models.Check` وجود ندارد؛ مقدار پیش‌فرض false است
        receivedCheckbox.isChecked = false

        var issueDateMillis = check.issueDate?.time ?: System.currentTimeMillis()
        var dueDateMillis = check.dueDate?.time ?: System.currentTimeMillis()

        val setIssueText: (Long) -> Unit = { ms ->
            val c = java.util.Calendar.getInstance(); c.timeInMillis = ms
            val p = com.persianai.assistant.utils.PersianDateConverter.gregorianToPersian(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
            issueDateButton.text = "📅 صدور: ${p.toReadableString()}"
        }
        val setDueText: (Long) -> Unit = { ms ->
            val c = java.util.Calendar.getInstance(); c.timeInMillis = ms
            val p = com.persianai.assistant.utils.PersianDateConverter.gregorianToPersian(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
            dueDateButton.text = "📅 سررسید: ${p.toReadableString()}"
        }

        setIssueText(issueDateMillis)
        setDueText(dueDateMillis)

        issueDateButton.setOnClickListener {
            val picker = com.persianai.assistant.ui.JalaliDatePickerDialog(this)
            picker.show(issueDateMillis) { sel ->
                issueDateMillis = sel
                setIssueText(issueDateMillis)
            }
        }

        dueDateButton.setOnClickListener {
            val picker = com.persianai.assistant.ui.JalaliDatePickerDialog(this)
            picker.show(dueDateMillis) { sel ->
                dueDateMillis = sel
                setDueText(dueDateMillis)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("ویرایش چک")
            .setView(view)
            .setPositiveButton("ذخیره") { _, _ ->
                val newNumber = checkNumberInput.text.toString()
                val newAmount = amountInput.text.toString().toDoubleOrNull() ?: check.amount
                val newRecipient = recipientInput.text.toString()
                val newBank = bankNameInput.text.toString()
                val newDesc = descriptionInput.text.toString()

                lifecycleScope.launch {
                    try {
                        // Update DB model via AccountingManager
                        val accMgr = accountingManager
                        val dbChecks = accMgr.getAllChecks().toMutableList()
                        val dbCheck = dbChecks.firstOrNull { it.checkNumber == check.checkNumber }
                        if (dbCheck != null) {
                            val updated = dbCheck.copy(
                                amount = newAmount,
                                checkNumber = newNumber,
                                recipient = newRecipient,
                                dueDate = java.util.Date(dueDateMillis),
                                description = newDesc,
                                issueDate = java.util.Date(issueDateMillis),
                                bankName = newBank
                            )
                            try { accMgr.updateCheck(updated) } catch (e: Exception) { android.util.Log.e("ProfessionalAccounting", "Error updating DB check", e) }
                        }

                        // Also update CheckManager's stored checks if present
                        try {
                            val cm = com.persianai.assistant.finance.CheckManager(this@ProfessionalAccountingActivity)
                            val localChecks = cm.getAllChecks()
                            val found = localChecks.firstOrNull { it.checkNumber == check.checkNumber }
                            if (found != null) {
                                val updatedLocal = found.copy(
                                    checkNumber = newNumber,
                                    amount = newAmount,
                                    recipient = newRecipient,
                                    bankName = newBank,
                                    issueDate = issueDateMillis,
                                    dueDate = dueDateMillis,
                                    description = newDesc
                                )
                                cm.updateCheck(updatedLocal)
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("ProfessionalAccounting", "Could not update local CheckManager store: ${e.message}")
                        }

                        // reload lists
                        loadFinancialData()
                        Toast.makeText(this@ProfessionalAccountingActivity, "✅ چک بروزرسانی شد", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@ProfessionalAccountingActivity, "خطا در بروزرسانی: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }
    
    private fun showInstallmentDialog(installment: Installment) {
        // نمایش جزئیات قسط
        Toast.makeText(this, "جزئیات قسط: ${installment.title}", Toast.LENGTH_SHORT).show()
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.accounting_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_export -> {
                exportData()
                true
            }
            R.id.action_backup -> {
                backupData()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun exportData() {
        lifecycleScope.launch {
            try {
                accountingManager.exportToCSV()
                Toast.makeText(this@ProfessionalAccountingActivity, "داده‌ها با موفقیت export شدند", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ProfessionalAccountingActivity, "خطا در export داده‌ها", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun backupData() {
        lifecycleScope.launch {
            try {
                accountingManager.createBackup()
                Toast.makeText(this@ProfessionalAccountingActivity, "پشتیبان با موفقیت ایجاد شد", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ProfessionalAccountingActivity, "خطا در ایجاد پشتیبان", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
