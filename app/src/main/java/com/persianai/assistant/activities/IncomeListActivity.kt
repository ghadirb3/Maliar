package com.persianai.assistant.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.persianai.assistant.adapters.TransactionAdapter
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.data.TransactionType
import com.persianai.assistant.databinding.ActivityIncomeListBinding
import com.persianai.assistant.utils.DialogUtils
import kotlinx.coroutines.launch

class IncomeListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomeListBinding
    private lateinit var db: AccountingDB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIncomeListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "درآمدها"

        db = AccountingDB(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        loadIncomes()
    }

    private fun loadIncomes() {
        lifecycleScope.launch {
            val incomes = db.getAllTransactions(TransactionType.INCOME)
            binding.recyclerView.adapter = TransactionAdapter(
                incomes.toMutableList(),
                onDeleteClick = { transaction ->
                    DialogUtils.showDeleteConfirmation(this@IncomeListActivity, "درآمد") {
                        lifecycleScope.launch {
                            db.deleteTransaction(transaction.id)
                            loadIncomes()
                            DialogUtils.showSuccessToast(this@IncomeListActivity, "✅ درآمد حذف شد")
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
            .setTitle("✏️ ویرایش درآمد")
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
                    loadIncomes()
                    android.widget.Toast.makeText(this@IncomeListActivity, "✅ درآمد ویرایش شد", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadIncomes()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
