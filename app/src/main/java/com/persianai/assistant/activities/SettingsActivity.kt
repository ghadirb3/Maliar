package com.persianai.assistant.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.persianai.assistant.databinding.ActivitySettingsBinding
import com.persianai.assistant.services.AIAssistantService
import com.persianai.assistant.utils.PreferencesManager
import com.persianai.assistant.utils.AutoProvisioningManager
import com.persianai.assistant.utils.DriveHelper
import com.persianai.assistant.utils.EncryptionHelper
import com.persianai.assistant.models.AIProvider
import com.persianai.assistant.models.APIKey
import com.persianai.assistant.ui.CallSettingsDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.material.button.MaterialButtonToggleGroup

/**
 * صفحه تنظیمات
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefsManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "تنظیمات"

        prefsManager = PreferencesManager(this)
        
        // Online-only: Hide offline model card
        binding.offlineModelCard.visibility = View.GONE
        binding.coquiTtsCard.visibility = View.GONE
        binding.changeModeButton.visibility = View.VISIBLE
        binding.currentModeText.text = "صفحه شروع: ${prefsManager.getStartDestination().name}"
        setupRecordingModeUI()

        loadSettings()
        setupListeners()
    }
    
    override fun onResume() {
        super.onResume()
        loadSettings()
    }

    private fun loadSettings() {
        val keys = prefsManager.getAPIKeys()
        val activeKeys = keys.filter { it.isActive }
        binding.apiKeysStatus.text = "کلیدهای فعال: ${activeKeys.size} از ${keys.size}"
        
        val currentModel = prefsManager.getSelectedModel()
        binding.currentModel.text = "مدل فعلی: ${currentModel.displayName}"
        
        val serviceEnabled = prefsManager.isServiceEnabled()
        binding.backgroundServiceSwitch.isChecked = serviceEnabled

        binding.persistentNotificationSwitch.isChecked = prefsManager.isPersistentStatusNotificationEnabled()
        binding.persistentNotificationActionsSwitch.isChecked = prefsManager.isPersistentNotificationActionsEnabled()
        
        binding.ttsSwitch.isChecked = prefsManager.isTTSEnabled()

        binding.currentModeText.text = "صفحه شروع: ${if (prefsManager.getStartDestination() == PreferencesManager.StartDestination.DASHBOARD) "داشبورد" else "دستیار"}"
        refreshRecordingModeUI()
    }

    private fun updateCurrentModeText() {
        val mode = prefsManager.getWorkingMode()
        val modeText = when (mode) {
            PreferencesManager.WorkingMode.ONLINE -> "آنلاین 🌐"
            PreferencesManager.WorkingMode.OFFLINE -> "آفلاین 📱"
            PreferencesManager.WorkingMode.HYBRID -> "ترکیبی ⚡"
        }
        binding.currentModeText.text = "حالت فعلی: $modeText"
    }

    private fun setupListeners() {
        binding.showWelcomeButton.setOnClickListener {
            val intent = Intent(this, WelcomeActivity::class.java)
            intent.putExtra("SHOW_HELP", true)
            startActivity(intent)
        }
        
        binding.changeModeButton.setOnClickListener {
            showStartDestinationDialog()
        }

        binding.manageAppsButton.setOnClickListener {
            try {
                val intent = Intent(this, ConnectedAppsActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "خطا: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        
        binding.callSettingsButton.setOnClickListener {
            CallSettingsDialog.show(this)
        }
        
        binding.accessibilityGuideButton.setOnClickListener {
            val intent = Intent(this, AccessibilityGuideActivity::class.java)
            startActivity(intent)
        }
        
        binding.refreshKeysButton.setOnClickListener {
            refreshKeysFromGist()
        }
        binding.clearKeysButton.setOnClickListener {
            showClearKeysDialog()
        }

        binding.backgroundServiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!prefsManager.isPersistentStatusNotificationEnabled()) {
                    prefsManager.setServiceEnabled(false)
                    binding.backgroundServiceSwitch.isChecked = false
                    Toast.makeText(this, "برای سرویس پس‌زمینه، نوتیفیکیشن وضعیت باید فعال باشد", Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                prefsManager.setServiceEnabled(true)
                startBackgroundService()
            } else {
                prefsManager.setServiceEnabled(false)
                stopBackgroundService()
            }
        }

        binding.persistentNotificationSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.setPersistentStatusNotificationEnabled(isChecked)
            if (!isChecked && prefsManager.isServiceEnabled()) {
                prefsManager.setServiceEnabled(false)
                binding.backgroundServiceSwitch.isChecked = false
                stopBackgroundService()
            }
        }

        binding.persistentNotificationActionsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.setPersistentNotificationActionsEnabled(isChecked)
            if (prefsManager.isServiceEnabled()) {
                startBackgroundService()
            }
        }
        binding.ttsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.setTTSEnabled(isChecked)
            Toast.makeText(this, if (isChecked) "اعلام صوتی پاسخ‌ها فعال شد" else "اعلام صوتی پاسخ‌ها غیرفعال شد", Toast.LENGTH_SHORT).show()
        }
        binding.backupButton.setOnClickListener {
            performBackup()
        }

        binding.restoreButton.setOnClickListener {
            performRestore()
        }

        binding.aboutButton.setOnClickListener {
            showAboutDialog()
        }

        binding.downloadCoquiTtsButton.setOnClickListener { /* no-op */ }
        binding.openCoquiDriveButton.setOnClickListener { /* no-op */ }

        binding.addOpenAiKeyButton.setOnClickListener {
            promptAddOpenAiKey()
        }
    }

    private fun setupRecordingModeUI() {
        try {
            refreshRecordingModeUI()
            binding.recordingModeToggle.addOnButtonCheckedListener { group: MaterialButtonToggleGroup, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                when (checkedId) {
                    binding.btnModeFast.id -> {
                        prefsManager.setRecordingMode(PreferencesManager.RecordingMode.FAST)
                        binding.recordingModeDesc.text = "حالت فعلی: سریع (ارسال خودکار)"
                        Toast.makeText(this, "حالت ضبط: سریع (ارسال خودکار)", Toast.LENGTH_SHORT).show()
                    }
                    binding.btnModePrecise.id -> {
                        prefsManager.setRecordingMode(PreferencesManager.RecordingMode.PRECISE)
                        binding.recordingModeDesc.text = "حالت فعلی: دقیق (تأیید دستی)"
                        Toast.makeText(this, "حالت ضبط: دقیق (تأیید دستی)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun refreshRecordingModeUI() {
        val mode = prefsManager.getRecordingMode()
        when (mode) {
            PreferencesManager.RecordingMode.FAST -> {
                binding.recordingModeDesc.text = "حالت فعلی: سریع (ارسال خودکار)"
                binding.recordingModeToggle.check(binding.btnModeFast.id)
            }
            PreferencesManager.RecordingMode.PRECISE -> {
                binding.recordingModeDesc.text = "حالت فعلی: دقیق (تأیید دستی)"
                binding.recordingModeToggle.check(binding.btnModePrecise.id)
            }
        }
    }

    private fun refreshKeysFromGist() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@SettingsActivity, "در حال دانلود و فعال‌سازی کلیدها...", Toast.LENGTH_SHORT).show()
                val result = AutoProvisioningManager.autoProvision(this@SettingsActivity)
                withContext(Dispatchers.Main) {
                    result.onSuccess { keys ->
                        loadSettings()
                        Toast.makeText(
                            this@SettingsActivity,
                            "✅ ${keys.count { it.isActive }} کلید فعال شد",
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { e ->
                        Toast.makeText(
                            this@SettingsActivity,
                            "❌ خطا در دانلود/فعال‌سازی: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "❌ خطا: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptAddOpenAiKey() {
        val editText = android.widget.EditText(this).apply {
            hint = "sk-proj-..."
            setSingleLine()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("افزودن کلید OpenAI (sk-proj-...)")
            .setView(editText)
            .setPositiveButton("ذخیره") { dialog, _ ->
                val token = editText.text?.toString()?.trim().orEmpty()
                if (token.startsWith("sk-proj-")) {
                    val key = APIKey(
                        provider = AIProvider.OPENAI,
                        key = token,
                        baseUrl = "https://api.openai.com/v1",
                        isActive = true
                    )
                    val all = prefsManager.getAPIKeys().toMutableList().apply {
                        add(key)
                    }
                    prefsManager.saveAPIKeys(all)
                    loadSettings()
                    Toast.makeText(this, "✅ کلید اضافه شد و فعال است", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ فرمت باید با sk-proj- شروع شود", Toast.LENGTH_LONG).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("انصراف") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    private fun showPasswordDialogForRefresh() { /* غیرفعال */ }
    
    private fun downloadAPIKeys(password: String) { /* غیرفعال */ }

    private fun showStartDestinationDialog() {
        val options = arrayOf("داشبورد", "دستیار")
        val current = prefsManager.getStartDestination()
        MaterialAlertDialogBuilder(this)
            .setTitle("انتخاب صفحه شروع")
            .setSingleChoiceItems(options, if (current == PreferencesManager.StartDestination.DASHBOARD) 0 else 1) { dialog, which ->
                val dest = if (which == 0) PreferencesManager.StartDestination.DASHBOARD else PreferencesManager.StartDestination.ASSISTANT
                prefsManager.setStartDestination(dest)
                binding.currentModeText.text = "صفحه شروع: ${options[which]}"
                dialog.dismiss()
            }
            .show()
    }
    
    private fun parseAPIKeys(data: String): List<APIKey> {
        val keys = mutableListOf<APIKey>()
        data.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEach
            val parts = trimmed.split(":", limit = 2)
            if (parts.size == 2) {
                val provider = when (parts[0].lowercase()) {
                    "openai" -> AIProvider.OPENAI
                    "anthropic", "claude" -> AIProvider.ANTHROPIC
                    "openrouter" -> AIProvider.OPENROUTER
                    "liara" -> AIProvider.LIARA
                    else -> null
                }
                if (provider != null) {
                    val token = parts[1].trim()
                    if (provider == AIProvider.LIARA) {
                        keys.add(APIKey(
                            provider = AIProvider.LIARA,
                            key = token,
                            baseUrl = "https://ai.liara.ir/api/69467b6ba99a2016cac892e1/v1",
                            isActive = false
                        ))
                    } else {
                        keys.add(APIKey(provider, token, isActive = false))
                    }
                }
            } else if (parts.size == 1 && trimmed.startsWith("sk-")) {
                val provider = when {
                    trimmed.startsWith("sk-proj-") -> AIProvider.OPENAI
                    trimmed.startsWith("sk-or-") -> AIProvider.OPENROUTER
                    trimmed.length == 51 && trimmed.startsWith("sk-") -> AIProvider.ANTHROPIC
                    else -> AIProvider.OPENAI
                }
                keys.add(APIKey(provider, trimmed, isActive = false))
            }
        }
        return keys
    }

    private fun showClearKeysDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("پاک کردن کلیدها")
            .setMessage("آیا مطمئن هستید که می‌خواهید تمام کلیدهای API را پاک کنید؟")
            .setPositiveButton("بله") { _, _ ->
                prefsManager.clearAPIKeys()
                loadSettings()
                Toast.makeText(this, "کلیدها پاک شدند", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("خیر", null)
            .show()
    }

    private fun startBackgroundService() {
        val intent = Intent(this, AIAssistantService::class.java)
        startForegroundService(intent)
        Toast.makeText(this, "سرویس پس‌زمینه فعال شد", Toast.LENGTH_SHORT).show()
    }

    private fun stopBackgroundService() {
        val intent = Intent(this, AIAssistantService::class.java)
        stopService(intent)
        Toast.makeText(this, "سرویس پس‌زمینه غیرفعال شد", Toast.LENGTH_SHORT).show()
    }

    private fun performBackup() {
        val options = arrayOf("📤 اشتراک‌گذاری (Gmail)", "💾 ذخیره در گوشی", "☁️ Google Drive")
        MaterialAlertDialogBuilder(this)
            .setTitle("انتخاب روش پشتیبان‌گیری")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> performShareBackup()
                    1 -> performLocalBackup()
                    2 -> performGoogleDriveBackup()
                }
            }
            .show()
    }
    
    private fun performShareBackup() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@SettingsActivity, "در حال بک‌آپ...", Toast.LENGTH_SHORT).show()
                withContext(Dispatchers.IO) {
                    val backupFile = com.persianai.assistant.utils.BackupManager.createBackup(this@SettingsActivity)
                    withContext(Dispatchers.Main) {
                        com.persianai.assistant.utils.BackupManager.shareBackup(this@SettingsActivity, backupFile)
                        Toast.makeText(this@SettingsActivity, "✅ بک‌آپ آماده است! Gmail را انتخاب کنید", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "خطا: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun performLocalBackup() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "maliar_backup_${System.currentTimeMillis()}.json")
        }
        startActivityForResult(intent, REQUEST_CODE_BACKUP_LOCAL)
    }
    
    private fun performGoogleDriveBackup() {
        lifecycleScope.launch {
            try {
                val cloudHelper = com.persianai.assistant.utils.CloudBackupHelper(this@SettingsActivity)
                if (!cloudHelper.isConnected()) {
                    val signInIntent = cloudHelper.getSignInIntent()
                    startActivityForResult(signInIntent, REQUEST_CODE_DRIVE_SIGN_IN)
                } else {
                    performDriveBackup()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "خطا در اتصال به Google Drive: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun performDriveBackup() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@SettingsActivity, "☁️ در حال آپلود به Google Drive...", Toast.LENGTH_SHORT).show()
                val backupJson = withContext(Dispatchers.IO) {
                    com.persianai.assistant.utils.BackupManager(this@SettingsActivity).createBackupJson()
                }
                val cloudHelper = com.persianai.assistant.utils.CloudBackupHelper(this@SettingsActivity)
                val result = cloudHelper.uploadBackup(backupJson)
                Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "❌ خطا: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performRestore() {
        val options = arrayOf("📂 از فایل ذخیره شده", "☁️ از Google Drive")
        MaterialAlertDialogBuilder(this)
            .setTitle("انتخاب روش بازیابی")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> performLocalRestore()
                    1 -> performDriveRestore()
                }
            }
            .show()
    }
    
    private fun performLocalRestore() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "انتخاب فایل بک‌آپ"), REQUEST_CODE_RESTORE)
    }
    
    private fun performDriveRestore() {
        lifecycleScope.launch {
            try {
                val cloudHelper = com.persianai.assistant.utils.CloudBackupHelper(this@SettingsActivity)
                if (!cloudHelper.isConnected()) {
                    val signInIntent = cloudHelper.getSignInIntent()
                    startActivityForResult(signInIntent, REQUEST_CODE_DRIVE_RESTORE)
                } else {
                    Toast.makeText(this@SettingsActivity, "☁️ در حال دانلود از Google Drive...", Toast.LENGTH_SHORT).show()
                    val jsonContent = withContext(Dispatchers.IO) { cloudHelper.downloadLatestBackup() }
                    if (jsonContent != null) {
                        val success = withContext(Dispatchers.IO) {
                            com.persianai.assistant.utils.BackupManager.restoreBackup(this@SettingsActivity, jsonContent)
                        }
                        if (success) {
                            Toast.makeText(this@SettingsActivity, "✅ بازیابی از Google Drive موفق!", Toast.LENGTH_SHORT).show()
                            loadSettings()
                        } else {
                            Toast.makeText(this@SettingsActivity, "❌ خطا در بازیابی", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@SettingsActivity, "❌ هیچ فایل بک‌آپی در Drive یافت نشد", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "❌ خطا: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_RESTORE -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri ->
                        lifecycleScope.launch {
                            try {
                                val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                if (content != null) {
                                    val success = withContext(Dispatchers.IO) {
                                        com.persianai.assistant.utils.BackupManager.restoreBackup(this@SettingsActivity, content)
                                    }
                                    if (success) {
                                        Toast.makeText(this@SettingsActivity, "✅ بازیابی موفق!", Toast.LENGTH_SHORT).show()
                                        loadSettings()
                                    } else {
                                        Toast.makeText(this@SettingsActivity, "❌ خطا در بازیابی", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(this@SettingsActivity, "خطا: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            REQUEST_CODE_BACKUP_LOCAL -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri ->
                        lifecycleScope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    com.persianai.assistant.utils.BackupManager(this@SettingsActivity).createLocalBackup(uri)
                                }
                                Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(this@SettingsActivity, "خطا: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            REQUEST_CODE_DRIVE_SIGN_IN -> {
                if (resultCode == RESULT_OK) {
                    performDriveBackup()
                }
            }
            REQUEST_CODE_DRIVE_RESTORE -> {
                if (resultCode == RESULT_OK) {
                    performDriveRestore()
                }
            }
        }
    }
    
    companion object {
        private const val REQUEST_CODE_RESTORE = 1001
        private const val REQUEST_CODE_BACKUP_LOCAL = 1002
        private const val REQUEST_CODE_DRIVE_SIGN_IN = 1003
        private const val REQUEST_CODE_DRIVE_RESTORE = 1004
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("درباره برنامه")
            .setMessage("""
                Persian AI Assistant
                نسخه 1.0.0
                
                یک دستیار هوش مصنوعی قدرتمند و چندمنظوره
                
                ویژگی‌ها:
                • استفاده از مدل‌های GPT-4o و Claude
                • تشخیص صوت و تحلیل فایل‌های صوتی
                • حافظه بلندمدت و پشتیبان‌گیری
                • سرویس پس‌زمینه
                
                توسعه‌دهنده: Ghadir
                GitHub: github.com/ghadirb/PersianAIAssistantOnline
            """.trimIndent())
            .setPositiveButton("باشه", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}