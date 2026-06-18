package com.persianai.assistant.activities

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.persianai.assistant.adapters.ChecksAdapter
import com.persianai.assistant.databinding.ActivityCheckListBinding
import com.persianai.assistant.finance.CheckManager
import com.persianai.assistant.utils.PersianDateConverter
import java.util.Calendar

class CheckListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckListBinding
    private lateinit var checkManager: CheckManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheckListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "چک‌ها"

        checkManager = CheckManager(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        loadChecks()
    }

    private fun loadChecks() {
        val checks = checkManager.getAllChecks()
        binding.recyclerView.adapter = ChecksAdapter(checks) { check ->
            showCheckDetails(check)
        }
    }

    private fun showCheckDetails(check: CheckManager.Check) {
        val calendar = Calendar.getInstance().apply { timeInMillis = check.dueDate }
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

        MaterialAlertDialogBuilder(this)
            .setTitle("جزئیات چک")
            .setMessage(
                "شماره: ${check.checkNumber}\n" +
                    "مبلغ: ${String.format("%,.0f", check.amount)} تومان\n" +
                    "گیرنده: ${check.recipient}\n" +
                    "سررسید: $persianDate\n" +
                    "وضعیت: $statusText"
            )
            .setPositiveButton("بستن", null)
            .apply {
                if (check.status == CheckManager.CheckStatus.PENDING) {
                    setNeutralButton("✅ پرداخت شد") { _, _ ->
                        checkManager.updateCheckStatus(check.id, CheckManager.CheckStatus.PAID)
                        Toast.makeText(this@CheckListActivity, "✅ چک پرداخت شد", Toast.LENGTH_SHORT).show()
                        loadChecks()
                    }
                }
            }
            .setNegativeButton("حذف") { _, _ ->
                checkManager.deleteCheck(check.id)
                Toast.makeText(this, "✅ چک حذف شد", Toast.LENGTH_SHORT).show()
                loadChecks()
            }
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
