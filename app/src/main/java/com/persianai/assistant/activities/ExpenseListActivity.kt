package com.persianai.assistant.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.persianai.assistant.adapters.TransactionAdapter
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.data.TransactionType
import com.persianai.assistant.databinding.ActivityExpenseListBinding
import com.persianai.assistant.utils.DialogUtils
import kotlinx.coroutines.launch

class ExpenseListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExpenseListBinding
    private lateinit var db: AccountingDB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExpenseListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "هزینه‌ها"

        db = AccountingDB(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        loadExpenses()
    }

    private fun loadExpenses() {
        lifecycleScope.launch {
            val expenses = db.getAllTransactions(TransactionType.EXPENSE)
            binding.recyclerView.adapter = TransactionAdapter(
                expenses.toMutableList(),
                onDeleteClick = { transaction ->
                    DialogUtils.showDeleteConfirmation(this@ExpenseListActivity, "هزینه") {
                        lifecycleScope.launch {
                            db.deleteTransaction(transaction.id)
                            loadExpenses()
                            DialogUtils.showSuccessToast(this@ExpenseListActivity, "✅ هزینه حذف شد")
                        }
                    }
                },
                onEditClick = { transaction ->
                    // Handle edit click
                    showEditDialog(transaction)
                }
            )
        }
    }
    
    private fun showEditDialog(transaction: com.persianai.assistant.data.Transaction) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        
        val descriptionInput = android.widget.EditText(this).apply {
            hint = "توضیح"
            setText(transaction.description)
        }
        val amountInput = android.widget.EditText(this).apply {
            hint = "مبلغ"
            setText(transaction.amount.toString())
            inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        
        container.addView(descriptionInput)
        container.addView(amountInput)
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("✏️ ویرایش هزینه")
            .setView(container)
            .setPositiveButton("ذخیره") { _, _ ->
                val newDescription = descriptionInput.text.toString()
                val newAmount = amountInput.text.toString().toDoubleOrNull() ?: transaction.amount
                
                lifecycleScope.launch {
                    val updated = transaction.copy(
                        description = newDescription,
                        amount = newAmount
                    )
                    db.updateTransaction(updated)
                    loadExpenses()
                    android.widget.Toast.makeText(this@ExpenseListActivity, "✅ هزینه ویرایش شد", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadExpenses()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
