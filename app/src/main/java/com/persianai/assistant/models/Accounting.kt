package com.persianai.assistant.models

import java.util.Date

/**
 * مدل‌های حسابداری حرفه‌ای
 */

// تراکنش مالی
data class FinancialTransaction(
    val id: Long = 0,
    val type: TransactionType,
    val category: TransactionCategory,
    val amount: Double,
    val description: String,
    val date: Date,
    val tags: List<String> = emptyList(),
    val isRecurring: Boolean = false,
    val recurringInterval: RecurringInterval? = null,
    val attachments: List<String> = emptyList()
)

enum class TransactionType {
    INCOME,    // درآمد
    EXPENSE,   // هزینه
    TRANSFER   // انتقال
}

enum class TransactionCategory {
    // درآمدها
    SALARY,           // حقوق
    BUSINESS,         // کسب و کار
    INVESTMENT,       // سرمایه‌گذاری
    RENTAL,           // اجاره
    GIFT,             // هدیه
    OTHER_INCOME,     // سایر درآمدها
    
    // هزینه‌ها
    FOOD,             // غذا و رستوران
    TRANSPORT,        // حمل و نقل
    SHOPPING,         // خرید
    BILLS,            // قبوض
    ENTERTAINMENT,    // سرگرمی
    HEALTH,           // بهداشت و درمان
    EDUCATION,        // آموزش
    HOUSING,          // مسکن
    INSURANCE,        // بیمه
    TAX,              // مالیات
    OTHER_EXPENSE     // سایر هزینه‌ها
}

enum class RecurringInterval {
    DAILY,    // روزانه
    WEEKLY,   // هفتگی
    MONTHLY,  // ماهانه
    YEARLY    // سالانه
}

// مدل چک
data class Check(
    val id: Long = 0,
    val checkNumber: String,
    val amount: Double,
    val recipient: String = "",
    val issuer: String = "",
    val issueDate: Date = Date(),
    val dueDate: Date = Date(),
    val status: CheckStatus = CheckStatus.PENDING,
    val description: String = "",
    val bankName: String = "",
    val accountNumber: String = "",
    val accountId: String = "",
    val isIncoming: Boolean = false
)

enum class CheckStatus {
    PENDING,   // در انتظار وصول
    DEPOSITED, // وصول شده
    BOUNCED,   // برگشت خورده
    CANCELLED  // لغو شده
}

// مدل قسط
data class Installment(
    val id: Long = 0,
    val title: String,
    val totalAmount: Double,
    val paidAmount: Double = 0.0,
    val remainingAmount: Double,
    val installmentCount: Int,
    val paidInstallments: Int = 0,
    val monthlyAmount: Double,
    val nextPaymentDate: Date,
    val status: InstallmentStatus,
    val description: String = "",
    val lender: String = "",
    val interestRate: Double = 0.0
)

enum class InstallmentStatus {
    ACTIVE,    // فعال
    COMPLETED, // تکمیل شده
    DELAYED,   // معوق
    CANCELLED  // لغو شده
}

// مدل بودجه
data class Budget(
    val id: Long = 0,
    val category: TransactionCategory,
    val allocatedAmount: Double,
    val spentAmount: Double = 0.0,
    val remainingAmount: Double,
    val period: BudgetPeriod,
    val startDate: Date,
    val endDate: Date
)

enum class BudgetPeriod {
    WEEKLY,   // هفتگی
    MONTHLY,  // ماهانه
    QUARTERLY, // فصلی
    YEARLY    // سالانه
}

// مدل حساب بانکی
data class BankAccount(
    val id: Long = 0,
    val accountName: String,
    val accountNumber: String,
    val bankName: String,
    val balance: Double,
    val accountType: AccountType,
    val isActive: Boolean = true
)

enum class AccountType {
    CHECKING,  // جاری
    SAVINGS,   // پس‌انداز
    CREDIT     // اعتباری
}

// مدل گزارش مالی
data class FinancialReport(
    val period: ReportPeriod,
    val startDate: Date,
    val endDate: Date,
    val totalIncome: Double,
    val totalExpense: Double,
    val netIncome: Double,
    val categoryBreakdown: Map<TransactionCategory, Double>,
    val monthlyTrend: List<MonthlyData>,
    val topExpenses: List<FinancialTransaction>,
    val topIncome: List<FinancialTransaction>
)

data class MonthlyData(
    val month: String,
    val income: Double,
    val expense: Double,
    val net: Double
)

enum class ReportPeriod {
    MONTHLY,  // ماهانه
    QUARTERLY, // فصلی
    YEARLY    // سالانه
}

// مدل یادآوری مالی
data class FinancialReminder(
    val id: Long = 0,
    val type: ReminderType,
    val title: String,
    val description: String,
    val dueDate: Date,
    val amount: Double? = null,
    val isRecurring: Boolean = false,
    val recurringInterval: RecurringInterval? = null,
    val isActive: Boolean = true,
    val notificationDaysBefore: Int = 1
)

enum class ReminderType {
    CHECK_PAYMENT,    // پرداخت چک
    INSTALLMENT_DUE,  // سررسید قسط
    BILL_PAYMENT,     // پرداخت قبض
    BUDGET_ALERT,     // هشدار بودجه
    SAVINGS_GOAL      // هدف پس‌انداز
}
