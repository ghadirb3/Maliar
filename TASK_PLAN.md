# Task Implementation Plan

## 1. 🔧 Fix Backup System (Google Drive + Local)
- **Problem**: Backup button gives error, no Google Drive option
- **Fix**: 
  - Overhaul BackupManager to include all data (transactions, reminders, checks, installments, feature flags)
  - Add Google Drive backup/restore with proper OAuth flow
  - Add a dialog to choose between Local/Google Drive backup

## 2. 💡 AI-Powered Smart Reminders
- **Problem**: Reminders are basic text only
- **Fix**:
  - Add "Smart Mode" to reminders that uses AI to generate natural language reminder text
  - Add fullscreen alarm with AI-generated friendly message
  - Add TTS playback for smart reminders (if TTS works)
  - Make reminders display in a smart, natural way

## 3. 💰 Fix Accounting Data Issues
- **Problem**: 
  - Monthly report shows wrong amounts (1,400,000 → 1,000,000)
  - Fragmented data storage (FinanceManager vs AccountingManager vs AccountingDB)
  - Missing monthly/yearly balance sheet reports
- **Fix**:
  - Unify data source - make AccountingManager the single source of truth
  - Fix the monthly report calculation precision
  - Add proper monthly/yearly balance reports
  - Ensure backup includes all accounting data

## 4. 🤖 Chat AI Access to Reminders & Accounting
- **Problem**: Chat model can't access reminders/accounting data
- **Fix**:
  - Enhance EnhancedSmartAssistant to properly route queries to ReminderModule and FinanceModule
  - Add system prompt context about user's current reminders and financial status
  - Enable the chat to create reminders and query financial data naturally

## 5. 🧪 Testing & Verification
- Test backup/restore with Google Drive
- Test AI-powered reminders
- Test accounting data integrity
- Test chat interaction with both modules