package com.persianai.assistant.activities

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.persianai.assistant.adapters.InstallmentsAdapter
import com.persianai.assistant.databinding.ActivityInstallmentListBinding
import com.persianai.assistant.finance.InstallmentManager

class InstallmentListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInstallmentListBinding
    private lateinit var installmentManager: InstallmentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstallmentListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "اقساط"

        installmentManager = InstallmentManager(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        loadInstallments()
    }

    private fun loadInstallments() {
        val installments = installmentManager.getAllInstallments()
        binding.recyclerView.adapter = InstallmentsAdapter(installments) { installment ->
            showInstallmentDetails(installment)
        }
    }

    private fun showInstallmentDetails(installment: InstallmentManager.Installment) {
        val remaining = installment.totalInstallments - installment.paidInstallments
        MaterialAlertDialogBuilder(this)
            .setTitle(installment.title)
            .setMessage(
                "مبلغ کل: ${String.format("%,.0f", installment.totalAmount)} تومان\n" +
                    "هر قسط: ${String.format("%,.0f", installment.installmentAmount)} تومان\n" +
                    "پرداخت شده: ${installment.paidInstallments} از ${installment.totalInstallments}\n" +
                    "باقیمانده: $remaining قسط"
            )
            .setPositiveButton("بستن", null)
            .apply {
                if (installment.paidInstallments < installment.totalInstallments) {
                    setNeutralButton("✅ ثبت پرداخت") { _, _ ->
                        installmentManager.payInstallment(installment.id)
                        Toast.makeText(this@InstallmentListActivity, "✅ پرداخت قسط ثبت شد", Toast.LENGTH_SHORT).show()
                        loadInstallments()
                    }
                }
            }
            .setNegativeButton("حذف") { _, _ ->
                installmentManager.deleteInstallment(installment.id)
                Toast.makeText(this, "✅ قسط حذف شد", Toast.LENGTH_SHORT).show()
                loadInstallments()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadInstallments()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
