package com.persianai.assistant.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.persianai.assistant.R
import com.persianai.assistant.call.CallConfirmationManager
import com.persianai.assistant.models.Contact
import kotlinx.coroutines.*

/**
 * صفحه تأیید تماس مالیار
 * نمایش اورلی تأیید تماس با گزینه‌های بزرگ و قابل دسترسی
 */
class CallConfirmationActivity : AppCompatActivity() {
    
    private lateinit var contactNameText: TextView
    private lateinit var phoneNumberText: TextView
    private lateinit var callButton: View
    private lateinit var cancelButton: View
    
    private var currentContact: Contact? = null
    private var confirmationManager: CallConfirmationManager? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // تنظیمات نمایش اورلی
        setupOverlayWindow()
        
        setContentView(R.layout.activity_call_confirmation)
        setupViews()
        
        // دریافت اطلاعات مخاطب از Intent
        handleIntent()
    }
    
    private fun setupOverlayWindow() {
        // نمایش روی سایر اپ‌ها
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        
        // حالت تمام صفحه یا اورلی
        window.attributes = window.attributes.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
    }
    
    private fun setupViews() {
        contactNameText = findViewById(R.id.contactNameText)
        phoneNumberText = findViewById(R.id.phoneNumberText)
        callButton = findViewById(R.id.callButton)
        cancelButton = findViewById(R.id.cancelButton)
        
        // کلیک دکمه تماس
        callButton.setOnClickListener {
            makeCall()
        }
        
        // کلیک دکمه لغو
        cancelButton.setOnClickListener {
            cancelCall()
        }
        
        // انیمیشن دکمه‌ها
        setupButtonAnimations()
    }
    
    private fun setupButtonAnimations() {
        // انیمیشن فشردن دکمه تماس
        callButton.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
            }
            false
        }
        
        // انیمیشن فشردن دکمه لغو
        cancelButton.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
            }
            false
        }
    }
    
    private fun handleIntent() {
        val contact = intent.getParcelableExtra<Contact>("contact")
        val phoneNumber = intent.getStringExtra("phone_number") ?: ""
        
        if (contact != null) {
            currentContact = contact
            displayContactInfo(contact)
        } else {
            // اگر مخاطب مشخص نبود، از شماره استفاده کن
            displayPhoneNumberOnly(phoneNumber)
        }
    }
    
    private fun displayContactInfo(contact: Contact) {
        contactNameText.text = contact.getDisplayName()
        phoneNumberText.text = contact.getFormattedNumber()
    }
    
    private fun displayPhoneNumberOnly(phoneNumber: String) {
        contactNameText.text = "تماس با شماره"
        phoneNumberText.text = phoneNumber
        currentContact = Contact.createTestContact("", phoneNumber)
    }
    
    private fun makeCall() {
        val contact = currentContact ?: return
        val phoneNumber = if (contact.phoneNumber.isNotBlank()) contact.phoneNumber else phoneNumberText.text.toString()
        
        if (phoneNumber.isNotBlank()) {
            // بازگرداندن نتیجه به فراخوانی
            val result = Intent().apply {
                putExtra("action", "call")
                putExtra("phone_number", phoneNumber)
            }
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }
    
    private fun cancelCall() {
        // بازگرداندن نتیجه لغو
        val result = Intent().apply {
            putExtra("action", "cancel")
        }
        setResult(Activity.RESULT_CANCELED, result)
        finish()
    }
    
    override fun onBackPressed() {
        // جلوگیری از بستن با دکمه بازگشت - فقط لغو تماس ممکن است
        cancelCall()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        confirmationManager?.cleanup()
    }
    
    companion object {
        private const val TAG = "CallConfirmationActivity"
        
        /**
         * شروع صفحه تأیید تماس
         */
        fun start(context: android.content.Context, contact: Contact) {
            val intent = Intent(context, CallConfirmationActivity::class.java).apply {
                putExtra("contact", contact)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
        
        /**
         * شروع صفحه تأیید تماس فقط با شماره تلفن
         */
        fun startWithPhoneNumber(context: android.content.Context, phoneNumber: String) {
            val intent = Intent(context, CallConfirmationActivity::class.java).apply {
                putExtra("phone_number", phoneNumber)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }
}
