package com.persianai.assistant.receivers

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.persianai.assistant.activities.DashboardActivity
import com.persianai.assistant.utils.NotificationHelper
import com.persianai.assistant.utils.SmartCallManager
import com.persianai.assistant.utils.PreferencesManager
import com.persianai.assistant.utils.ContactInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * BroadcastReceiver برای مدیریت اکشن‌های نوتیفیکیشن تماس
 */
class CallNotificationReceiver : BroadcastReceiver() {
    
    private val scope = CoroutineScope(Dispatchers.Main)
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        Log.d("CallNotificationReceiver", "Received action: $action")
        
        when (action) {
            "MAKE_CALL" -> handleMakeCall(context, intent)
            "OPEN_DIALER" -> handleOpenDialer(context, intent)
            "CHANGE_CONTACT" -> handleChangeContact(context, intent)
            "SELECT_CONTACT" -> handleSelectContact(context, intent)
            "REQUEST_CONTACTS_PERMISSION" -> handleRequestPermission(context, intent)
        }
    }
    
    /**
     * مدیریت تماس مستقیم
     */
    private fun handleMakeCall(context: Context, intent: Intent) {
        val phoneNumber = intent.getStringExtra("phone_number") ?: return
        val contactName = intent.getStringExtra("contact_name") ?: "مخاطب"
        
        Log.d("CallNotificationReceiver", "Making call to $phoneNumber")
        
        try {
            // بررسی تنظیمات کاربر برای نحوه تماس
            val prefsManager = PreferencesManager(context)
            val directCall = prefsManager.getDirectCallEnabled() // باید این متد اضافه شود
            
            if (directCall) {
                // تماس مستقیم
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(callIntent)
                
                Log.d("CallNotificationReceiver", "Direct call initiated")
            } else {
                // باز کردن صفحه شماره‌گیر
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(dialIntent)
                
                Log.d("CallNotificationReceiver", "Dialer opened")
            }
            
            // نمایش نوتیفیکیشن تأیید
            showCallConfirmationNotification(context, contactName, phoneNumber, directCall)
            
        } catch (e: Exception) {
            Log.e("CallNotificationReceiver", "Error making call", e)
            showErrorNotification(context, "خطا در برقراری تماس: ${e.message}")
        }
        
        // لغو نوتیفیکیشن تماس
        SmartCallManager.cancelCallNotification(context)
    }
    
    /**
     * مدیریت باز کردن صفحه شماره‌گیر
     */
    private fun handleOpenDialer(context: Context, intent: Intent) {
        val phoneNumber = intent.getStringExtra("phone_number") ?: return
        
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            
            Log.d("CallNotificationReceiver", "Dialer opened with number: $phoneNumber")
            
        } catch (e: Exception) {
            Log.e("CallNotificationReceiver", "Error opening dialer", e)
            showErrorNotification(context, "خطا در باز کردن شماره‌گیر: ${e.message}")
        }
        
        SmartCallManager.cancelCallNotification(context)
    }
    
    /**
     * مدیریت تغییر مخاطب
     */
    private fun handleChangeContact(context: Context, intent: Intent) {
        val contactName = intent.getStringExtra("contact_name") ?: return
        
        // باز کردن برنامه در صفحه تماس
        val mainIntent = Intent(context, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_call_screen", true)
            putExtra("contact_name", contactName)
        }
        context.startActivity(mainIntent)
        
        SmartCallManager.cancelCallNotification(context)
    }
    
    /**
     * مدیریت انتخاب مخاطب از لیست
     */
    private fun handleSelectContact(context: Context, intent: Intent) {
        val contactsJson = intent.getStringExtra("contacts_json") ?: return
        
        try {
            // اینجا باید یک Activity برای انتخاب مخاطب باز شود
            // فعلاً برنامه اصلی را باز می‌کنیم
            val mainIntent = Intent(context, DashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("open_contact_selection", true)
                putExtra("contacts_json", contactsJson)
            }
            context.startActivity(mainIntent)
            
        } catch (e: Exception) {
            Log.e("CallNotificationReceiver", "Error handling contact selection", e)
            showErrorNotification(context, "خطا در انتخاب مخاطب: ${e.message}")
        }
        
        SmartCallManager.cancelCallNotification(context)
    }
    
    /**
     * مدیریت درخواست مجوز مخاطبین
     */
    private fun handleRequestPermission(context: Context, intent: Intent) {
        val contactName = intent.getStringExtra("contact_name") ?: return
        
        try {
            // باز کردن تنظیمات برنامه برای دادن مجوز
            val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(settingsIntent)
            
            // نمایش نوتیفیکیشن راهنما
            showPermissionGuideNotification(context, contactName)
            
        } catch (e: Exception) {
            Log.e("CallNotificationReceiver", "Error requesting permission", e)
            showErrorNotification(context, "خطا در درخواست مجوز: ${e.message}")
        }
        
        SmartCallManager.cancelCallNotification(context)
    }
    
    /**
     * نمایش نوتیفیکیشن تأیید تماس
     */
    private fun showCallConfirmationNotification(
        context: Context,
        contactName: String,
        phoneNumber: String,
        wasDirectCall: Boolean
    ) {
        val actionText = if (wasDirectCall) "تماس برقرار شد" else "صفحه شماره‌گیر باز شد"
        
        NotificationHelper.showGeneralNotification(
            context = context,
            title = "📞 $actionText",
            message = "با «$contactName» (شماره: $phoneNumber)",
            notificationId = 6000
        )
    }
    
    /**
     * نمایش نوتیفیکیشن خطا
     */
    private fun showErrorNotification(context: Context, message: String) {
        NotificationHelper.showGeneralNotification(
            context = context,
            title = "❌ خطا در تماس",
            message = message,
            notificationId = 6001
        )
    }
    
    /**
     * نمایش نوتیفیکیشن راهنمای مجوز
     */
    private fun showPermissionGuideNotification(context: Context, contactName: String) {
        NotificationHelper.showGeneralNotification(
            context = context,
            title = "📋 راهنمای مجوز مخاطبین",
            message = "1. در تنظیمات، «مجوزها» را انتخاب کنید\n2. «مخاطبین» را پیدا کرده و فعال کنید\n3. دوباره تلاش کنید: تماس با «$contactName»",
            notificationId = 6002
        )
    }
}
