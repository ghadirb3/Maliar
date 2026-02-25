package com.persianai.assistant.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import com.persianai.assistant.R
import com.persianai.assistant.utils.PreferencesManager

/**
 * دیالوگ تنظیمات حالت تماس
 */
class CallSettingsDialog(context: Context) : AppCompatDialog(context) {

    private val prefsManager = PreferencesManager(context)
    private lateinit var radioGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_call_settings, null)
        setContentView(view)
        
        setupViews(view)
        setupClickListeners()
        
        // تنظیم حالت فعلی
        val currentMode = prefsManager.getCallMode()
        when (currentMode) {
            PreferencesManager.CallMode.DIALER -> {
                view.findViewById<android.widget.RadioButton>(R.id.radioDialer).isChecked = true
            }
            PreferencesManager.CallMode.DIRECT -> {
                view.findViewById<android.widget.RadioButton>(R.id.radioDirect).isChecked = true
            }
        }
    }
    
    private fun setupViews(view: View) {
        radioGroup = view.findViewById(R.id.callModeRadioGroup)
    }
    
    private fun setupClickListeners() {
        findViewById<View>(R.id.btnSave)?.setOnClickListener {
            saveSettings()
        }
        
        findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dismiss()
        }
    }
    
    private fun saveSettings() {
        val selectedMode = when (radioGroup.checkedRadioButtonId) {
            R.id.radioDialer -> PreferencesManager.CallMode.DIALER
            R.id.radioDirect -> PreferencesManager.CallMode.DIRECT
            else -> PreferencesManager.CallMode.DIALER // پیش‌فرض
        }
        
        prefsManager.setCallMode(selectedMode)
        
        val message = when (selectedMode) {
            PreferencesManager.CallMode.DIALER -> "تنظیمات تماس روی شماره‌گیر گوشی تنظیم شد"
            PreferencesManager.CallMode.DIRECT -> "تنظیمات تماس روی تماس مستقیم تنظیم شد"
        }
        
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        dismiss()
    }
    
    companion object {
        fun show(context: Context) {
            CallSettingsDialog(context).show()
        }
    }
}
