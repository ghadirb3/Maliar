package com.persianai.assistant.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import com.persianai.assistant.utils.FormatUtils
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
    private val checks = mutableListOf<CheckManager.Check>()
    
    private var filterType: CheckFilterType = CheckFilterType.ALL
    
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
    }
    
    private fun loadChecks() {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                
                val allChecks = checkManager.getAllChecks()
                
                checks.clear()
                checks.addAll(allChecks)
                
                applyFilter(filterType)
                
                binding.progressBar.visibility = View.GONE
                
                if (checks.isEmpty()) {
                    binding.checksRecyclerView.visibility = View.GONE
                } else {
                    binding.checksRecyclerView.visibility = View.VISIBLE
                }
                
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
    
    private fun applyFilter(type: CheckFilterType) {
        filterType = type
        checksAdapter.notifyDataSetChanged()
    }
    
    private fun updateStats() {
        lifecycleScope.launch {
            try {
                binding.statsCard.visibility = View.VISIBLE
            } catch (e: Exception) {
                // Stats not available
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
        
        MaterialAlertDialogBuilder(this)
            .setTitle("جزئیات چک")
            .setMessage(details)
            .setPositiveButton("بستن", null)
            .setNegativeButton("حذف") { _, _ ->
                deleteCheck(check)
            }
            .show()
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
    
    private fun formatAmount(amount: Double): String = FormatUtils.formatMoneyWithUnit(amount)
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        return super.onCreateOptionsMenu(menu)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}