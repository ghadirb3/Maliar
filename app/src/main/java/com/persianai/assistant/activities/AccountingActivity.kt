package com.persianai.assistant.activities

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.persianai.assistant.R
import com.persianai.assistant.databinding.ActivityAccountingBinding
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.data.Transaction
import com.persianai.assistant.data.TransactionType
import com.persianai.assistant.utils.SharedDataManager
import com.persianai.assistant.ai.AdvancedPersianAssistant
import com.persianai.assistant.adapters.TransactionAdapter
import com.persianai.assistant.finance.FinanceVoiceIntent
import com.persianai.assistant.finance.FinanceVoiceParser
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import android.widget.Toast

class AccountingActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAccountingBinding
    private lateinit var db: AccountingDB
    private lateinit var financeManager: com.persianai.assistant.finance.FinanceManager
    private lateinit var aiAssistant: AdvancedPersianAssistant
    private lateinit var incomeAdapter: TransactionAdapter
    private lateinit var expenseAdapter: TransactionAdapter
    private lateinit var checksAdapter: TransactionAdapter
    private lateinit var installmentsAdapter: TransactionAdapter

    private val incomeTransactions = mutableListOf<Transaction>()
    private val expenseTransactions = mutableListOf<Transaction>()
    private val checkTransactions = mutableListOf<Transaction>()
    private val installmentTransactions = mutableListOf<Transaction>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "💰 حسابداری"
        
        db = AccountingDB(this)
        financeManager = com.persianai.assistant.finance.FinanceManager(this)
        aiAssistant = AdvancedPersianAssistant(this)
        
        setupUI()
        setupRecyclerView()
        updateBalance()
        loadTransactions()
    }

    private fun showVoiceCommandDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "مثال: چک ۲۰ میلیون برای علی ثبت کن"
            setPadding(32, 32, 32, 32)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("🎙️ فرمان فارسی سریع")
            .setView(input)
            .setPositiveButton("ثبت") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) {
                    Toast.makeText(this, "لطفاً متن فرمان را وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val intent = FinanceVoiceParser.parse(text)
                handleVoiceIntent(intent)
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun handleVoiceIntent(intent: FinanceVoiceIntent) {
        when (intent) {
            is FinanceVoiceIntent.AddExpenseIntent -> {
                lifecycleScope.launch {
                    val transaction = Transaction(
                        id = 0,
                        type = TransactionType.EXPENSE,
                        amount = intent.amount,
                        category = intent.description ?: "هزینه صوتی",
                        description = intent.description ?: "ثبت سریع"
                    )
                    financeManager.addTransaction(transaction.amount, transaction.type.name.lowercase().let { if (it == "check_in" ) "income" else if (it == "check_out") "expense" else if (it == "installment") "expense" else it }, transaction.category, transaction.description)
                    Toast.makeText(this@AccountingActivity, "✅ هزینه ثبت شد", Toast.LENGTH_SHORT).show()
                    updateBalance()
                    loadTransactions()
                }
            }
            is FinanceVoiceIntent.AddCheckIntent -> {
                lifecycleScope.launch {
                    val desc = intent.recipient?.let { "چک برای $it" } ?: "چک صوتی"
                    val transaction = Transaction(
                        id = 0,
                        type = TransactionType.CHECK_OUT,
                        amount = intent.amount,
                        category = "چک خودکار",
                        description = desc,
                        checkNumber = "VOICE-${System.currentTimeMillis()}"
                    )
                    financeManager.addTransaction(transaction.amount, transaction.type.name.lowercase().let { if (it == "check_in" ) "income" else if (it == "check_out") "expense" else if (it == "installment") "expense" else it }, transaction.category, transaction.description)
                    Toast.makeText(this@AccountingActivity, "✅ چک جدید ثبت شد", Toast.LENGTH_SHORT).show()
                    updateBalance()
                    loadTransactions()
                }
            }
            is FinanceVoiceIntent.AddInstallmentIntent -> {
                lifecycleScope.launch {
                    val monthly = intent.monthlyAmount ?: run {
                        val months = intent.totalMonths ?: 1
                        (intent.amount / months).coerceAtLeast(0.0)
                    }
                    val monthsLabel = intent.totalMonths?.let { "$it ماه" } ?: "نامشخص"
                    val transaction = Transaction(
                        id = 0,
                        type = TransactionType.INSTALLMENT,
                        amount = monthly,
                        category = intent.title,
                        description = "قسط خودکار ($monthsLabel)"
                    )
                    financeManager.addTransaction(transaction.amount, transaction.type.name.lowercase().let { if (it == "check_in" ) "income" else if (it == "check_out") "expense" else if (it == "installment") "expense" else it }, transaction.category, transaction.description)
                    Toast.makeText(this@AccountingActivity, "✅ قسط صوتی ثبت شد", Toast.LENGTH_SHORT).show()
                    updateBalance()
                    loadTransactions()
                }
            }
            FinanceVoiceIntent.UnknownIntent -> {
                Toast.makeText(this, "نتوانستم فرمان را تشخیص دهم", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setupUI() {
        binding.addIncomeButton.setOnClickListener { showAddDialog(TransactionType.INCOME) }
        binding.addExpenseButton.setOnClickListener { showAddDialog(TransactionType.EXPENSE) }
        binding.addCheckButton.setOnClickListener { showCheckDialog() }
        binding.addInstallmentButton.setOnClickListener { showInstallmentDialog() }
        binding.aiChatButton.setOnClickListener { showAIChat() }
    }
    
    private fun showAddTransactionDialog() {
        // Default to expense
        showAddDialog(TransactionType.EXPENSE)
    }
    
    private fun showAddDialog(type: TransactionType) {
        val view = layoutInflater.inflate(R.layout.dialog_add_transaction, null)
        val amountField = view.findViewById<TextInputEditText>(R.id.amountField)
        val categoryField = view.findViewById<TextInputEditText>(R.id.categoryField)
        val descField = view.findViewById<TextInputEditText>(R.id.descriptionField)
        
        MaterialAlertDialogBuilder(this)
            .setTitle(if (type == TransactionType.INCOME) "➥ درآمد جدید" else "➖ هزینه جدید")
            .setView(view)
            .setPositiveButton("ثبت") { _, _ ->
                val amount = amountField.text.toString().toDoubleOrNull() ?: 0.0
                val category = categoryField.text.toString()
                val desc = descField.text.toString()
                
                if (amount > 0) {
                    lifecycleScope.launch {
                        val transaction = Transaction(
                            id = 0,
                            type = type,
                            amount = amount,
                            category = category,
                            description = desc,
                            date = System.currentTimeMillis()
                        )
                        financeManager.addTransaction(transaction.amount, transaction.type.name.lowercase().let { if (it == "check_in" ) "income" else if (it == "check_out") "expense" else if (it == "installment") "expense" else it }, transaction.category, transaction.description)
                        updateBalance()
                    }
                } else {
                    Toast.makeText(this, "مبلغ نامعتبر است", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }
    
    private fun showCheckDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_check, null)
        val checkNumberInput = view.findViewById<TextInputEditText>(R.id.checkNumberInput)
        val amountInput = view.findViewById<TextInputEditText>(R.id.amountInput)
        val issuerInput = view.findViewById<TextInputEditText>(R.id.issuerInput)
        val recipientInput = view.findViewById<TextInputEditText>(R.id.recipientInput)
        val issueDateButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.issueDateButton)
        val dueDateButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.dueDateButton)
        val bankNameInput = view.findViewById<TextInputEditText>(R.id.bankNameInput)
        val accountNumberInput = view.findViewById<TextInputEditText>(R.id.accountNumberInput)
        val descriptionInput = view.findViewById<TextInputEditText>(R.id.descriptionInput)
        val receivedCheckbox = view.findViewById<android.widget.CheckBox>(R.id.receivedCheckbox)

        var issueDateMillis = System.currentTimeMillis()
        var dueDateMillis = System.currentTimeMillis()

        // Show Persian dates on buttons initially
        run {
            val cal = java.util.Calendar.getInstance()
            val persianIssue = com.persianai.assistant.utils.PersianDateConverter.gregorianToPersian(
                cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            issueDateButton.text = "📅 صدور: ${persianIssue.toReadableString()}"
            
            val persianDue = com.persianai.assistant.utils.PersianDateConverter.gregorianToPersian(
                cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            dueDateButton.text = "📅 سررسید: ${persianDue.toReadableString()}"
        }

        issueDateButton.setOnClickListener {
            val picker = com.persianai.assistant.ui.JalaliDatePickerDialog(this)
            picker.show(issueDateMillis) { millis ->
                issueDateMillis = millis
                val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
                val persian = com.persianai.assistant.utils.PersianDateConverter.gregorianToPersian(
                    c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH)
                )
                issueDateButton.text = "📅 صدور: ${persian.toReadableString()}"
            }
        }

        dueDateButton.setOnClickListener {
            val picker = com.persianai.assistant.ui.JalaliDatePickerDialog(this)
            picker.show(dueDateMillis) { millis ->
                dueDateMillis = millis
                val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
                val persian = com.persianai.assistant.utils.PersianDateConverter.gregorianToPersian(
                    c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH)
                )
                dueDateButton.text = "📅 سررسید: ${persian.toReadableString()}"
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("📝 چک جدید")
            .setView(view)
            .setPositiveButton("ثبت") { _, _ ->
                val amount = amountInput.text.toString().toDoubleOrNull() ?: 0.0
                val checkNum = checkNumberInput.text.toString()
                val issuer = issuerInput.text.toString()
                val recipient = recipientInput.text.toString()
                val bank = bankNameInput.text.toString()
                val accNum = accountNumberInput.text.toString()
                val desc = descriptionInput.text.toString()
                val isReceived = receivedCheckbox.isChecked

                if (amount > 0 && checkNum.isNotBlank()) {
                    // Save via CheckManager and record a transaction (income/expense)
                    try {
                        val cm = com.persianai.assistant.finance.CheckManager(this)
                        cm.addCheck(
                            checkNum,
                            amount,
                            issuer,
                            recipient,
                            issueDateMillis,
                            dueDateMillis,
                            bank,
                            accNum,
                            desc,
                            isIncoming = isReceived
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("AccountingActivity", "Error saving check", e)
                    }

                    lifecycleScope.launch {
                        val txType = if (isReceived) "income" else "expense"
                        financeManager.addTransaction(amount, txType, "چک $checkNum", desc)
                        updateBalance()
                        loadTransactions()
                        Toast.makeText(this@AccountingActivity, "✅ چک ثبت شد", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@AccountingActivity, "لطفاً شماره و مبلغ چک را وارد کنید", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }
    
    private fun showInstallmentDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_transaction, null)
        val amountField = view.findViewById<TextInputEditText>(R.id.amountField)
        val categoryField = view.findViewById<TextInputEditText>(R.id.categoryField)
        val descField = view.findViewById<TextInputEditText>(R.id.descriptionField)
        
        categoryField.hint = "تعداد اقساط"
        descField.hint = "توضیحات"
        
        MaterialAlertDialogBuilder(this)
            .setTitle("📊 قسط جدید")
            .setView(view)
            .setPositiveButton("ثبت") { _, _ ->
                val amount = amountField.text.toString().toDoubleOrNull() ?: 0.0
                val months = categoryField.text.toString().toIntOrNull() ?: 1
                val desc = descField.text.toString()
                
                if (amount > 0) {
                    lifecycleScope.launch {
                        val transaction = Transaction(
                            id = 0,
                            type = TransactionType.INSTALLMENT,
                            amount = amount / months,
                            category = "قسط $months ماهه",
                            description = desc,
                            date = System.currentTimeMillis()
                        )
                        financeManager.addTransaction(transaction.amount, transaction.type.name.lowercase().let { if (it == "check_in" ) "income" else if (it == "check_out") "expense" else if (it == "installment") "expense" else it }, transaction.category, transaction.description)
                        updateBalance()
                        loadTransactions()
                        Toast.makeText(this@AccountingActivity, "✅ قسط ثبت شد", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }
    
    private fun showAIChat() {
        val input = android.widget.EditText(this).apply {
            hint = "دستور خود را بنویسید (مثل: درآمد 500000 تومان ثبت کن)"
            setPadding(32, 32, 32, 32)
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("🤖 دستیار مالی هوشمند")
            .setView(input)
            .setPositiveButton("اجرا") { _, _ ->
                val userMessage = input.text.toString()
                if (userMessage.isNotEmpty()) {
                    lifecycleScope.launch {
                        try {
                            val parsed = FinanceVoiceParser.parse(userMessage)
                            if (parsed !is FinanceVoiceIntent.UnknownIntent) {
                                // ثبت خودکار تراکنش از طریق همان منطق handleVoiceIntent
                                handleVoiceIntent(parsed)
                            } else {
                                val response = aiAssistant.processRequestWithAI(
                                    userMessage,
                                    contextHint = "حسابداری شخصی، تراکنش‌ها، درآمد و هزینه، گزارش مالی ساده"
                                )

                                runOnUiThread {
                                    MaterialAlertDialogBuilder(this@AccountingActivity)
                                        .setTitle("پاسخ دستیار")
                                        .setMessage(response.text)
                                        .setPositiveButton("باشه", null)
                                        .show()
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this@AccountingActivity, "خطا: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }
    
    private fun setupRecyclerView() {
        val onDeleteClick: (Transaction) -> Unit = { transaction ->
            MaterialAlertDialogBuilder(this)
                .setTitle("❌ حذف تراکنش")
                .setMessage("آیا از حذف این تراکنش مطمئن هستید؟")
                .setPositiveButton("حذف") { _, _ ->
                    lifecycleScope.launch {
                        financeManager.deleteTransaction(transaction.id.toString())
                        loadTransactions() // Reload all lists
                        updateBalance()
                        Toast.makeText(this@AccountingActivity, "✅ تراکنش حذف شد", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("لغو", null)
                .show()
        }

        incomeAdapter = TransactionAdapter(incomeTransactions, onDeleteClick)
        binding.incomeRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AccountingActivity)
            adapter = incomeAdapter
        }

        expenseAdapter = TransactionAdapter(expenseTransactions, onDeleteClick)
        binding.expenseRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AccountingActivity)
            adapter = expenseAdapter
        }

        checksAdapter = TransactionAdapter(checkTransactions, onDeleteClick)
        binding.checksRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AccountingActivity)
            adapter = checksAdapter
        }

        installmentsAdapter = TransactionAdapter(installmentTransactions, onDeleteClick)
        binding.installmentsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AccountingActivity)
            adapter = installmentsAdapter
        }
    }
    
    private fun loadTransactions() {
        lifecycleScope.launch {
            val allTransactions = db.getAllTransactions()

            incomeTransactions.clear()
            incomeTransactions.addAll(allTransactions.filter { it.type == TransactionType.INCOME })
            incomeAdapter.notifyDataSetChanged()

            expenseTransactions.clear()
            expenseTransactions.addAll(allTransactions.filter { it.type == TransactionType.EXPENSE })
            expenseAdapter.notifyDataSetChanged()

            checkTransactions.clear()
            checkTransactions.addAll(allTransactions.filter { it.type == TransactionType.CHECK_IN || it.type == TransactionType.CHECK_OUT })
            checksAdapter.notifyDataSetChanged()

            installmentTransactions.clear()
            installmentTransactions.addAll(allTransactions.filter { it.type == TransactionType.INSTALLMENT })
            installmentsAdapter.notifyDataSetChanged()
        }
    }
    
    private fun updateBalance() {
        lifecycleScope.launch {
            val balance = db.getBalance()
            binding.totalBalanceText.text = String.format("%,.0f تومان", balance)
            
            // ذخیره در SharedDataManager
            SharedDataManager.saveTotalBalance(this@AccountingActivity, balance)
            
            // ذخیره هزینه و درآمد ماهانه
            val expenses = db.getMonthlyExpenses()
            val income = db.getMonthlyIncome()
            SharedDataManager.saveMonthlyExpenses(this@AccountingActivity, expenses)
            SharedDataManager.saveMonthlyIncome(this@AccountingActivity, income)
            
            val monthNet = income - expenses
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val yearlyReport = db.getYearlyReport(year)
            val yearIncome = yearlyReport[TransactionType.INCOME.name] ?: 0.0
            val yearExpenses = yearlyReport[TransactionType.EXPENSE.name] ?: 0.0
            val yearNet = yearIncome - yearExpenses

            binding.monthlySummaryText.text = String.format(
                "ماه جاری: درآمد %,.0f - هزینه %,.0f = %,.0f تومان",
                income,
                expenses,
                monthNet
            )

            binding.yearlySummaryText.text = String.format(
                "سال جاری: درآمد %,.0f - هزینه %,.0f = %,.0f تومان",
                yearIncome,
                yearExpenses,
                yearNet
            )

            android.util.Log.d(
                "AccountingActivity",
                "💾 داده‌ها به SharedDataManager sync شد: Balance=$balance, Expenses=$expenses, Income=$income, YearIncome=$yearIncome, YearExpenses=$yearExpenses"
            )
        }
    }
}
