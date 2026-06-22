# Fix Plan Based on Code Analysis

## Found Issues:

### 1. ProfessionalAccountingActivity - Check Edit Missing Issuer Field
- `showCheckDialog()` finds `issuerInput` but NEVER sets `issuerInput.setText(check.issuer)`
- Also `accountNumberInput` and `receivedCheckbox` are not set

### 2. Installment Payment Day Calculation Bug
- `InstallmentManager.calculateNextPaymentDate()` uses `calendar.add(Calendar.MONTH, paid)` THEN `calendar.set(Calendar.DAY_OF_MONTH, paymentDay)`
- If startDate day > paymentDay, the first payment shows wrong date
- Example: startDate = 1403/1/15, paymentDay = 2 → first payment shows as 1403/1/2 (past!)

### 3. Installment paymentDayInput Default
- No default value for paymentDay in new installment dialog (starts empty)
- Should default to 1

### 4. SmartReminderManager - Need to check for crash
- "Smart reminder" selected → app crashes/closes

### 5. Call stuck at "در حال پردازش فرمان"
- Need to trace the call flow

### 6. Reminder date picker issue
- Old RemindersActivity only has time input, no date
- AdvancedRemindersActivity uses JalaliDatePickerDialog correctly

## Fix Priority:
1. ✅ Fix check edit issuer display in ProfessionalAccountingActivity
2. ✅ Fix installment payment day calculation
3. ✅ Fix installment paymentDayInput default to 1
4. Fix smart reminder crash (need more investigation)
5. Fix call stuck issue (need more investigation)
6. Add Jalali date picker to old RemindersActivity