package com.persianai.assistant.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.persianai.assistant.R
import com.persianai.assistant.databinding.ActivityAccountingAdvancedBinding
import com.persianai.assistant.finance.CheckManager
import com.persianai.assistant.finance.FinanceManager
import com.persianai.assistant.finance.InstallmentManager
import com.persianai.assistant.utils.PersianDateConverter
import com.persianai.assistant.utils.SharedDataManager
import java.util.Calendar

class AccountingAdvancedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOW_ADD = "show_add"
    }

    private lateinit var binding: ActivityAccountingAdvancedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountingAdvancedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "💼 حسابداری پیشرفته"

        binding.btnIncomes.setOnClickListener {
            startActivity(Intent(this, IncomeListActivity::class.java))
        }

        binding.btnExpenses.setOnClickListener {
            startActivity(Intent(this, ExpenseListActivity::class.java))
        }

        binding.btnChecks.setOnClickListener {
            startActivity(Intent(this, ChecksManagementActivity::class.java))
        }

        binding.btnInstallments.setOnClickListener {
            startActivity(Intent(this, InstallmentsManagementActivity::class.java))
        }

        binding.chatFab.setOnClickListener {
            startActivity(Intent(this, AccountingChatActivity::class.java))
        }
        
        binding.btnMonthlyBalance.setOnClickListener {
            showMonthlyBalance()
        }
        
        binding.btnYearlyBalance.setOnClickListener {
            showYearlyBalance()
        }
        
        binding.btnAddIncomeManual.setOnClickListener {
            showManualInputDialog("درآمد", "income")
        }
        
        binding.btnAddExpenseManual.setOnClickListener {
            showManualInputDialog("هزینه", "expense")
        }
        
        binding.btnAddCheckManual.setOnClickListener {
            showAddCheckDialog()
        }
        
        binding.btnAddInstallmentManual.setOnClickListener {
            showAddInstallmentDialog()
        }
        
        updateStats()
    }
    
    override fun onResume() {
        super.onResume()
        updateStats()
    }
    
    private fun updateStats() {
        val financeManager = FinanceManager(this)
        val checkManager = CheckManager(this)
        val installmentManager = InstallmentManager(this)
        
        val transactions = financeManager.getAllTransactions()
        var totalIncome = 0.0
        var totalExpense = 0.0
        for (transaction in transactions) {
            if (transaction.type == "income") totalIncome += transaction.amount
            else if (transaction.type == "expense") totalExpense += transaction.amount
        }
        
        val pendingChecks = checkManager.getTotalPendingAmount()
        val remainingInstallments = installmentManager.getTotalRemainingAmount()
        
        binding.incomeAmount.text = "💰 ${String.format("%,.0f", totalIncome)} تومان"
        binding.expenseAmount.text = "💸 ${String.format("%,.0f", totalExpense)} تومان"
        binding.checksAmount.text = "📋 ${String.format("%,.0f", pendingChecks)} تومان (در انتظار)"
        binding.installmentsAmount.text = "💳 ${String.format("%,.0f", remainingInstallments)} تومان (باقیمانده)"
    }
    
    private fun showMonthlyBalance() {
        val financeManager = FinanceManager(this)
        val calendar = Calendar.getInstance()
        val message = financeManager.getMonthlyBalanceReport(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1
        )
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun showYearlyBalance() {
        val financeManager = FinanceManager(this)
        val message = financeManager.getYearlyBalanceReport(Calendar.getInstance().get(Calendar.YEAR))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun showManualInputDialog(type: String, action: String) {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("ورود دستی $type")
        
        val input = android.widget.EditText(this)
        input.hint = "مبلغ را وارد کنید"
        input.inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        builder.setView(input)
        
        builder.setPositiveButton("ثبت") { _, _ ->
            val amount = input.text.toString().toDoubleOrNull() ?: 0.0
            if (amount > 0) {
                val financeManager = FinanceManager(this)
                when (action) {
                    "income" -> {
                        financeManager.addTransaction(amount, "income", "درآمد", "ورود دستی")
                        Toast.makeText(this, "✅ درآمد ثبت شد", Toast.LENGTH_SHORT).show()
                    }
                    "expense" -> {
                        financeManager.addTransaction(amount, "expense", "هزینه", "ورود دستی")
                        Toast.makeText(this, "✅ هزینه ثبت شد", Toast.LENGTH_SHORT).show()
                    }
                }
                updateStats()
            }
        }
        builder.setNegativeButton("لغو", null)
        builder.show()
    }

    private fun showAddCheckDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_check, null)
        val amountInput = dialogView.findViewById<TextInputEditText>(R.id.amountInput)
        val checkNumberInput = dialogView.findViewById<TextInputEditText>(R.id.checkNumberInput)
        val issuerInput = dialogView.findViewById<TextInputEditText>(R.id.issuerInput)
        val recipientInput = dialogView.findViewById<TextInputEditText>(R.id.recipientInput)
        val bankNameInput = dialogView.findViewById<TextInputEditText>(R.id.bankNameInput)
        val dueDateButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.dueDateButton)
        val receivedCheckbox = dialogView.findViewById<android.widget.CheckBox>(R.id.receivedCheckbox)

        var selectedDueDate = System.currentTimeMillis()
        // Set initial Persian date display
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedDueDate }
        val persianDate = PersianDateConverter.gregorianToPersian(
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH)
        )
        dueDateButton.text = persianDate.toReadableString()
        dueDateButton.setOnClickListener {
            val picker = com.persianai.assistant.ui.JalaliDatePickerDialog(this)
            picker.show(selectedDueDate) { millis ->
                selectedDueDate = millis
                val calendar = Calendar.getInstance().apply { timeInMillis = millis }
                val persianDate = PersianDateConverter.gregorianToPersian(
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH) + 1,
                    calendar.get(Calendar.DAY_OF_MONTH)
                )
                dueDateButton.text = persianDate.toReadableString()
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("📋 ثبت چک جدید")
            .setView(dialogView)
            .setPositiveButton("ثبت") { _, _ ->
                val amount = amountInput.text.toString().toDoubleOrNull() ?: 0.0
                if (amount <= 0) {
                    Toast.makeText(this, "⚠️ مبلغ را وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val checkManager = CheckManager(this)
                checkManager.addCheck(
                    checkNumber = checkNumberInput.text.toString().ifBlank { System.currentTimeMillis().toString() },
                    amount = amount,
                    issuer = issuerInput.text.toString(),
                    recipient = recipientInput.text.toString().ifBlank { "نامشخص" },
                    issueDate = System.currentTimeMillis(),
                    dueDate = selectedDueDate,
                    bankName = bankNameInput.text.toString(),
                    accountNumber = "",
                    description = "ثبت دستی از حسابداری",
                    isReceived = receivedCheckbox.isChecked
                )
                Toast.makeText(this, "✅ چک ثبت شد", Toast.LENGTH_SHORT).show()
                updateStats()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun showAddInstallmentDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val titleInput = android.widget.EditText(this).apply { hint = "عنوان (مثلاً وام خودرو)" }
        val totalInput = android.widget.EditText(this).apply {
            hint = "مبلغ کل"
            inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val monthlyInput = android.widget.EditText(this).apply {
            hint = "مبلغ هر قسط"
            inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val countInput = android.widget.EditText(this).apply {
            hint = "تعداد اقساط"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val dayInput = android.widget.EditText(this).apply {
            hint = "روز پرداخت ماه شمسی (۱ تا ۳۱)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
        }

        container.addView(titleInput)
        container.addView(totalInput)
        container.addView(monthlyInput)
        container.addView(countInput)
        container.addView(dayInput)

        MaterialAlertDialogBuilder(this)
            .setTitle("💳 ثبت قسط جدید")
            .setView(container)
            .setPositiveButton("ثبت") { _, _ ->
                val title = titleInput.text.toString().ifBlank { "قسط" }
                val totalAmount = totalInput.text.toString().toDoubleOrNull() ?: 0.0
                val monthlyAmount = monthlyInput.text.toString().toDoubleOrNull() ?: 0.0
                val totalInstallments = countInput.text.toString().toIntOrNull() ?: 0
                val paymentDay = dayInput.text.toString().toIntOrNull()?.coerceIn(1, 31) ?: 1

                if (totalAmount <= 0 || monthlyAmount <= 0 || totalInstallments <= 0) {
                    Toast.makeText(this, "⚠️ اطلاعات را کامل وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                InstallmentManager(this).addInstallment(
                    title = title,
                    totalAmount = totalAmount,
                    installmentAmount = monthlyAmount,
                    totalInstallments = totalInstallments,
                    startDate = System.currentTimeMillis(),
                    paymentDay = paymentDay,
                    recipient = "",
                    description = "ثبت دستی از حسابداری"
                )
                Toast.makeText(this, "✅ قسط ثبت شد", Toast.LENGTH_SHORT).show()
                updateStats()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
