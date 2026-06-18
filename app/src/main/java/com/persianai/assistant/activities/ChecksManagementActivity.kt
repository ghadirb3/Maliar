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
import com.persianai.assistant.R
import com.persianai.assistant.adapters.ChecksAdapter
import com.persianai.assistant.databinding.ActivityChecksManagementBinding
import com.persianai.assistant.finance.CheckManager
import com.persianai.assistant.utils.PersianDateConverter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * ماژول مدیریت جامع چک‌ها
 * 
 * ✅ ثبت چک پرداختی/دریافتی
 * ✅ تاریخ سررسید
 * ✅ هشدارهای هوشمند
 * ✅ وضعیت چک
 * ✅ یادداشت و پیگیری
 */
class ChecksManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChecksManagementBinding
    private lateinit var checksAdapter: ChecksAdapter
    private lateinit var checkManager: CheckManager
    private val allChecks = mutableListOf<CheckManager.Check>()
    private val checks = mutableListOf<CheckManager.Check>()
    
    private var statusFilter: CheckFilterType? = null
    private var showPayable = false
    private var showReceivable = false
    
    enum class CheckFilterType {
        ALL,           // همه
        PAYABLE,       // پرداختی
        RECEIVABLE,    // دریافتی
        PENDING,       // در انتظار
        CASHED,        // پاس شده
        BOUNCED,       // برگشتی
        UPCOMING       // سررسید نزدیک
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChecksManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        initializeManager()
        setupRecyclerView()
        setupListeners()
        loadChecks()
        updateStats()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "💳 مدیریت چک‌ها"
    }
    
    private fun initializeManager() {
        checkManager = CheckManager(this)
    }
    
    private fun setupRecyclerView() {
        checksAdapter = ChecksAdapter(checks) { check ->
            viewCheckDetails(check)
        }
        
        binding.checksRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ChecksManagementActivity)
            adapter = checksAdapter
        }
    }
    
    private fun setupListeners() {
        binding.fabAddCheck.setOnClickListener {
            showAddCheckDialog()
        }

        binding.chipPayable.setOnClickListener {
            showPayable = binding.chipPayable.isChecked
            applyFilter()
        }

        binding.chipReceivable.setOnClickListener {
            showReceivable = binding.chipReceivable.isChecked
            applyFilter()
        }

        binding.statusChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            statusFilter = when {
                checkedIds.contains(R.id.chipPending) -> CheckFilterType.PENDING
                checkedIds.contains(R.id.chipPassed) -> CheckFilterType.CASHED
                checkedIds.contains(R.id.chipReturned) -> CheckFilterType.BOUNCED
                else -> null
            }
            applyFilter()
        }
    }
    
    private fun loadChecks() {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                
                allChecks.clear()
                allChecks.addAll(checkManager.getAllChecks())
                
                applyFilter()
                
                binding.progressBar.visibility = View.GONE
                updateEmptyState()
                
                updateStats()
                
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this@ChecksManagementActivity,
                    "❌ خطا در بارگذاری: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun applyFilter() {
        var filtered = allChecks.asSequence()

        if (showPayable || showReceivable) {
            filtered = filtered.filter { check ->
                val isPayable = check.issuer.isNotBlank()
                val isReceivable = check.recipient.isNotBlank() && check.issuer.isBlank()
                (showPayable && isPayable) || (showReceivable && isReceivable)
            }
        }

        filtered = when (statusFilter) {
            CheckFilterType.PENDING -> filtered.filter { it.status == CheckManager.CheckStatus.PENDING }
            CheckFilterType.CASHED -> filtered.filter { it.status == CheckManager.CheckStatus.PAID }
            CheckFilterType.BOUNCED -> filtered.filter { it.status == CheckManager.CheckStatus.BOUNCED }
            CheckFilterType.UPCOMING -> filtered.filter {
                it.status == CheckManager.CheckStatus.PENDING &&
                    it.dueDate <= System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)
            }
            else -> filtered
        }

        checks.clear()
        checks.addAll(filtered.sortedBy { it.dueDate })
        checksAdapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (checks.isEmpty()) {
            binding.checksRecyclerView.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyView.text = if (allChecks.isEmpty()) {
                "📭 هیچ چکی ثبت نشده است\n\nبرای افزودن چک جدید، دکمه + را بزنید"
            } else {
                "🔍 چکی با این فیلتر یافت نشد"
            }
        } else {
            binding.checksRecyclerView.visibility = View.VISIBLE
            binding.emptyView.visibility = View.GONE
        }
    }
    
    private fun updateStats() {
        lifecycleScope.launch {
            try {
                if (allChecks.isEmpty()) {
                    binding.statsCard.visibility = View.GONE
                    binding.statsText.text = ""
                    return@launch
                }

                binding.statsCard.visibility = View.VISIBLE
                val pending = allChecks.filter { it.status == CheckManager.CheckStatus.PENDING }
                val pendingAmount = pending.sumOf { it.amount }
                val paidAmount = allChecks.filter { it.status == CheckManager.CheckStatus.PAID }.sumOf { it.amount }
                val bouncedCount = allChecks.count { it.status == CheckManager.CheckStatus.BOUNCED }
                val upcomingCount = pending.count {
                    it.dueDate <= System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)
                }

                binding.statsText.text = buildString {
                    appendLine("تعداد کل: ${allChecks.size}")
                    appendLine("در انتظار: ${pending.size} (${formatAmount(pendingAmount)})")
                    appendLine("پرداخت شده: ${formatAmount(paidAmount)}")
                    if (bouncedCount > 0) appendLine("برگشتی: $bouncedCount")
                    if (upcomingCount > 0) appendLine("سررسید ۷ روز آینده: $upcomingCount")
                }
            } catch (e: Exception) {
                binding.statsCard.visibility = View.GONE
            }
        }
    }
    
    private fun showAddCheckDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_check, null)
        
        // Views
        val amountInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.amountInput)
        val checkNumberInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.checkNumberInput)
        val issuerInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.issuerInput)
        val recipientInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.recipientInput)
        val bankNameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.bankNameInput)
        val accountNumberInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.accountNumberInput)
        val descriptionInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.descriptionInput)
        val issueDateButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.issueDateButton)
        val dueDateButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.dueDateButton)
        
        var selectedDueDate: Long = System.currentTimeMillis()
        var selectedIssueDate: Long = System.currentTimeMillis()
        
        issueDateButton.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("تاریخ صدور")
                .setSelection(selectedIssueDate)
                .build()
            
            datePicker.addOnPositiveButtonClickListener { selection ->
                selectedIssueDate = selection
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = selection
                }
                val persianDate = PersianDateConverter.gregorianToPersian(
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH) + 1,
                    calendar.get(Calendar.DAY_OF_MONTH)
                )
                issueDateButton.text = persianDate.toReadableString()
            }
            
            datePicker.show(supportFragmentManager, "ISSUE_DATE_PICKER")
        }
        
        dueDateButton.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("تاریخ سررسید")
                .setSelection(selectedDueDate)
                .build()
            
            datePicker.addOnPositiveButtonClickListener { selection ->
                selectedDueDate = selection
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = selection
                }
                val persianDate = PersianDateConverter.gregorianToPersian(
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH) + 1,
                    calendar.get(Calendar.DAY_OF_MONTH)
                )
                dueDateButton.text = persianDate.toReadableString()
            }
            
            datePicker.show(supportFragmentManager, "DUE_DATE_PICKER")
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("➕ افزودن چک جدید")
            .setView(dialogView)
            .setPositiveButton("ذخیره") { _, _ ->
                val amount = amountInput.text.toString().toLongOrNull() ?: 0L
                val checkNumber = checkNumberInput.text.toString()
                val issuer = issuerInput.text.toString()
                val recipient = recipientInput.text.toString()
                val bankName = bankNameInput.text.toString()
                val accountNumber = accountNumberInput.text.toString()
                val description = descriptionInput.text.toString()
                
                if (amount <= 0) {
                    Toast.makeText(this, "⚠️ مبلغ را وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                if (checkNumber.isEmpty()) {
                    Toast.makeText(this, "⚠️ شماره چک را وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                addCheck(checkNumber, amount, issuer, recipient, selectedIssueDate, selectedDueDate, bankName, accountNumber, description)
            }
            .setNegativeButton("لغو", null)
            .show()
    }
    
    private fun addCheck(
        checkNumber: String,
        amount: Long,
        issuer: String,
        recipient: String,
        issueDate: Long,
        dueDate: Long,
        bankName: String,
        accountNumber: String,
        description: String
    ) {
        lifecycleScope.launch {
            try {
                checkManager.addCheck(
                    checkNumber = checkNumber,
                    amount = amount.toDouble(),
                    issuer = issuer,
                    recipient = recipient,
                    issueDate = issueDate,
                    dueDate = dueDate,
                    bankName = bankName,
                    accountNumber = accountNumber,
                    description = description
                )
                
                Toast.makeText(
                    this@ChecksManagementActivity,
                    "✅ چک با موفقیت ثبت شد",
                    Toast.LENGTH_SHORT
                ).show()
                
                loadChecks()
                
            } catch (e: Exception) {
                Toast.makeText(
                    this@ChecksManagementActivity,
                    "❌ خطا در ثبت: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun viewCheckDetails(check: CheckManager.Check) {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = check.dueDate
        }
        val persianDate = PersianDateConverter.gregorianToPersian(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        ).toReadableString()
        
        val statusText = when (check.status) {
            CheckManager.CheckStatus.PENDING -> "⏳ در انتظار"
            CheckManager.CheckStatus.PAID -> "✅ پرداخت شده"
            CheckManager.CheckStatus.BOUNCED -> "❌ برگشتی"
            CheckManager.CheckStatus.CANCELLED -> "🚫 لغو شده"
        }
        
        val details = buildString {
            appendLine("شماره چک: ${check.checkNumber}")
            appendLine("مبلغ: ${formatAmount(check.amount)}")
            appendLine("دارنده: ${check.recipient}")
            appendLine("تاریخ سررسید: $persianDate")
            appendLine("وضعیت: $statusText")
        }
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("جزئیات چک")
            .setMessage(details)

        when (check.status) {
            CheckManager.CheckStatus.PENDING -> {
                dialog
                    .setPositiveButton("✅ پرداخت شد") { _, _ ->
                        checkManager.updateCheckStatus(check.id, CheckManager.CheckStatus.PAID)
                        Toast.makeText(this, "✅ چک پرداخت شد و به هزینه‌ها اضافه شد", Toast.LENGTH_SHORT).show()
                        loadChecks()
                    }
                    .setNeutralButton("❌ برگشتی") { _, _ ->
                        checkManager.updateCheckStatus(check.id, CheckManager.CheckStatus.BOUNCED)
                        Toast.makeText(this, "❌ چک برگشتی ثبت شد", Toast.LENGTH_SHORT).show()
                        loadChecks()
                    }
                    .setNegativeButton("حذف") { _, _ -> deleteCheck(check) }
            }
            CheckManager.CheckStatus.PAID -> {
                dialog
                    .setPositiveButton("↩️ برگشت به انتظار") { _, _ ->
                        checkManager.updateCheckStatus(check.id, CheckManager.CheckStatus.PENDING)
                        Toast.makeText(this, "⏳ وضعیت چک به «در انتظار» برگشت", Toast.LENGTH_SHORT).show()
                        loadChecks()
                    }
                    .setNegativeButton("حذف") { _, _ -> deleteCheck(check) }
            }
            else -> {
                dialog
                    .setPositiveButton("بستن", null)
                    .setNegativeButton("حذف") { _, _ -> deleteCheck(check) }
            }
        }

        dialog.show()
    }
    
    private fun deleteCheck(check: CheckManager.Check) {
        MaterialAlertDialogBuilder(this)
            .setTitle("حذف چک")
            .setMessage("آیا از حذف این چک مطمئن هستید؟")
            .setPositiveButton("بله") { _, _ ->
                lifecycleScope.launch {
                    try {
                        checkManager.deleteCheck(check.id)
                        
                        Toast.makeText(
                            this@ChecksManagementActivity,
                            "✅ چک حذف شد",
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        loadChecks()
                        
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@ChecksManagementActivity,
                            "❌ خطا در حذف: ${e.message}",
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
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.checks_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_export -> {
                exportChecks()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportChecks() {
        val csv = checkManager.exportToCSV()
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "گزارش چک‌های Maliar")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(shareIntent, "اشتراک‌گذاری گزارش چک‌ها"))
    }
}