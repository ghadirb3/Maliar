package com.persianai.assistant.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.persianai.assistant.adapters.CheckAdapter
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.databinding.ActivityCheckListBinding
import com.persianai.assistant.utils.DialogUtils
import kotlinx.coroutines.launch

class CheckListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckListBinding
    private lateinit var db: AccountingDB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheckListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "چک‌ها"

        db = AccountingDB(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        loadChecks()
    }

    private fun loadChecks() {
        lifecycleScope.launch {
            val checks = db.getAllChecks()
            binding.recyclerView.adapter = CheckAdapter(
                onCheckClick = { check ->
                    // Handle check click
                },
                onDeleteClick = { check ->
                    DialogUtils.showDeleteConfirmation(this@CheckListActivity, "چک") {
                        lifecycleScope.launch {
                            db.deleteCheck(check.id)
                            loadChecks()
                            DialogUtils.showSuccessToast(this@CheckListActivity, "✅ چک حذف شد")
                        }
                    }
                },
                onEditClick = { check ->
                    // Handle edit click
                    showEditDialog(check)
                }
            ).apply {
                submitList(checks)
            }
        }
    }
    
    private fun showEditDialog(check: com.persianai.assistant.models.Check) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        
        val amountInput = android.widget.EditText(this).apply {
            hint = "مبلغ"
            setText(check.amount.toString())
            inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val recipientInput = android.widget.EditText(this).apply {
            hint = "گیرنده"
            setText(check.recipient)
        }
        
        container.addView(amountInput)
        container.addView(recipientInput)
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("✏️ ویرایش چک")
            .setView(container)
            .setPositiveButton("ذخیره") { _, _ ->
                val newAmount = amountInput.text.toString().toDoubleOrNull() ?: check.amount
                val newRecipient = recipientInput.text.toString()
                
                lifecycleScope.launch {
                    val updated = check.copy(
                        amount = newAmount,
                        recipient = newRecipient
                    )
                    db.updateCheck(updated)
                    loadChecks()
                    android.widget.Toast.makeText(this@CheckListActivity, "✅ چک ویرایش شد", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadChecks()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
