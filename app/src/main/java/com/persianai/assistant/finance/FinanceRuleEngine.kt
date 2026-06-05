package com.persianai.assistant.finance

import android.content.Context
import com.persianai.assistant.utils.FormatUtils
import com.persianai.assistant.utils.PersianDateConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID

/**
 * Rule-based analyzer for upcoming financial obligations (checks & installments).
 */
class FinanceRuleEngine(private val context: Context) {

    private val checkManager = CheckManager(context)
    private val installmentManager = InstallmentManager(context)

    enum class Severity { INFO, WARNING, CRITICAL }
    enum class Category { CHECK, INSTALLMENT, CASHFLOW }

    data class FinanceAlert(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val description: String,
        val severity: Severity,
        val category: Category
    )

    data class EvaluationResult(
        val alerts: List<FinanceAlert>,
        val recommendations: List<String>
    )

    suspend fun evaluate(daysAhead: Int = 14): EvaluationResult = withContext(Dispatchers.Default) {
        val alerts = mutableListOf<FinanceAlert>()
        val recommendations = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val horizon = now + daysAhead * DAY

        val checks = checkManager.getAllChecks()
        val installments = installmentManager.getAllInstallments()

        checks.forEach { check ->
            when {
                check.dueDate < now && check.status == CheckManager.CheckStatus.PENDING -> alerts.add(
                    FinanceAlert(
                        title = "چک معوق ${check.checkNumber}",
                        description = "${formatAmount(check.amount)} تومان - دریافت‌کننده: ${check.recipient}",
                        severity = Severity.CRITICAL,
                        category = Category.CHECK
                    )
                )
                check.dueDate in now..horizon && check.status == CheckManager.CheckStatus.PENDING -> alerts.add(
                    FinanceAlert(
                        title = "سررسید نزدیک برای چک ${check.checkNumber}",
                        description = "${formatAmount(check.amount)} تومان تا ${formatDate(check.dueDate)}",
                        severity = Severity.WARNING,
                        category = Category.CHECK
                    )
                )
            }

            if (check.amount >= HIGH_VALUE_THRESHOLD && check.status == CheckManager.CheckStatus.PENDING) {
                recommendations.add("برای چک ${check.checkNumber} با مبلغ ${formatAmount(check.amount)} تومان ذخیره نقدی داشته باشید.")
            }
        }

        installments.forEach { installment ->
            val nextPayment = installmentManager.calculateNextPaymentDate(installment)
            if (nextPayment != null && installment.paidInstallments < installment.totalInstallments) {
                when {
                    nextPayment < now -> alerts.add(
                        FinanceAlert(
                            title = "قسط عقب‌افتاده: ${installment.title}",
                            description = "${formatAmount(installment.installmentAmount)} تومان باید پرداخت شود.",
                            severity = Severity.CRITICAL,
                            category = Category.INSTALLMENT
                        )
                    )
                    nextPayment in now..horizon -> alerts.add(
                        FinanceAlert(
                            title = "قسط در راه: ${installment.title}",
                            description = "${formatAmount(installment.installmentAmount)} تومان تا ${formatDate(nextPayment)}",
                            severity = Severity.WARNING,
                            category = Category.INSTALLMENT
                        )
                    )
                }
            }

            val completionPercent = (installment.paidInstallments.toDouble() / installment.totalInstallments) * 100
            if (completionPercent >= 80 && installment.paidInstallments < installment.totalInstallments) {
                recommendations.add("تنها ${installment.totalInstallments - installment.paidInstallments} قسط برای ${installment.title} باقی‌مانده است؛ برای تسویه زودتر برنامه‌ریزی کنید.")
            }
        }

        if (alerts.isEmpty()) {
            recommendations.add("هیچ تعهد بحرانی ثبت نشده است. می‌توانید برای سرمایه‌گذاری برنامه‌ریزی کنید.")
        }

        EvaluationResult(alerts, recommendations)
    }

    private fun formatAmount(amount: Double): String = FormatUtils.formatMoney(amount)

    private fun formatDate(timestamp: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val persian = PersianDateConverter.gregorianToPersian(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        return persian.toReadableString()
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val HIGH_VALUE_THRESHOLD = 50_000_000.0 // 50 میلیون تومان
    }
}
