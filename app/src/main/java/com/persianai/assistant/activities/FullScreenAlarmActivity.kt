package com.persianai.assistant.activities

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.GestureDetectorCompat
import com.persianai.assistant.R
import com.persianai.assistant.utils.SmartReminderManager
import com.persianai.assistant.utils.SmartReminderSpeechHelper
import com.persianai.assistant.utils.TTSHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Activity تمام‌صفحه با سوایپ بهبود شده
 * - سوایپ راست: انجام شد ✅
 * - سوایپ چپ: تعویق ⏰
 * 
 * نسخه 4.0 - بهبود‌های اصلی:
 * ✅ Swipe detection بهتر
 * ✅ Persistence اطلاعات روی فایل
 * ✅ Touch event handling صحیح
 * ✅ Full Screen Intent برای Background
 * ✅ WakeLock مدیریت شده
 * ✅ Display over other apps برای Android 10+
 */
class FullScreenAlarmActivity : Activity() {
    
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var smartReminderId: String? = null
    private var isSmartReminder: Boolean = false
    private var ttsHelper: TTSHelper? = null
    private val TAG = "FullScreenAlarm"
    
    private lateinit var rootLayout: FrameLayout
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var leftSwipeHint: TextView
    private lateinit var rightSwipeHint: TextView
    private lateinit var leftIcon: ImageView
    private lateinit var rightIcon: ImageView
    private lateinit var swipeHandle: ImageView
    private lateinit var swipeHandleGlow: ImageView
    private lateinit var leftHintIcon: ImageView
    private lateinit var rightHintIcon: ImageView
    
    private var isActionTaken = false
    private var currentSwipeDirection = 0 // 0=none, 1=left, 2=right
    private var swipeProgress = 0f
    private var downX = 0f
    private var downY = 0f
    private val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val MIN_SWIPE_DISTANCE = 100
    private val MIN_SWIPE_VELOCITY = 100
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "🚀 onCreate started - PID: ${android.os.Process.myPid()}")
        Log.d(TAG, "📦 Intent extras: ${intent.extras?.keySet()}")
        Log.d(TAG, "📦 Title: ${intent.getStringExtra("title")}")
        Log.d(TAG, "📦 Description: ${intent.getStringExtra("description")}")
        Log.d(TAG, "📦 SmartID: ${intent.getStringExtra("smart_reminder_id")}")
        
        try {
            setupWindow()
            setContentView(R.layout.activity_full_screen_alarm)
            
            // بررسی آیا یادآوری هوشمند است (دارای برچسب smart:true)
            val tags = intent.getStringArrayListExtra("tags") ?: arrayListOf()
            isSmartReminder = tags.contains("smart:true") || 
                            intent.getBooleanExtra("is_smart_reminder", false)
            
            Log.d(TAG, "🧠 isSmartReminder=$isSmartReminder")
            
            initializeViews()
            setupGestureDetector()
            setupUI()
            
            // برای یادآوری هوشمند: ابتدا TTS پخش شود، سپس آلارم
            if (isSmartReminder) {
                startSmartReminderTTS()
            } else {
                startAlarmEffects()
            }
            
            showSwipeHints()
            
            Log.d(TAG, "✅ onCreate completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onCreate", e)
            e.printStackTrace()
            finish()
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "📱 onResume called")
        setupImmersiveMode()
        dismissKeyguard()
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "🪟 onWindowFocusChanged: hasFocus=$hasFocus")
        if (hasFocus) {
            setupImmersiveMode()
        }
    }
    
    private fun setupImmersiveMode() {
        try {
            val decorView = window.decorView
            val flags = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            decorView.systemUiVisibility = flags
            Log.d(TAG, "✅ Immersive mode set")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting immersive mode", e)
        }
    }
    
    private fun dismissKeyguard() {
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                keyguardManager.requestDismissKeyguard(this, null)
            }
            Log.d(TAG, "✅ Keyguard dismiss requested")
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing keyguard", e)
        }
    }
    
    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        
        @Suppress("DEPRECATION")
        window.apply {
            addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }
        
        Log.d(TAG, "✅ Window setup complete")
    }
    
    private fun initializeViews() {
        try {
            rootLayout = findViewById(R.id.alarm_root)
            leftSwipeHint = findViewById(R.id.left_swipe_hint)
            rightSwipeHint = findViewById(R.id.right_swipe_hint)
            leftIcon = findViewById(R.id.left_icon)
            rightIcon = findViewById(R.id.right_icon)
            swipeHandle = findViewById(R.id.swipe_handle)
            swipeHandleGlow = findViewById(R.id.swipe_handle_glow)
            leftHintIcon = findViewById(R.id.left_hint_icon)
            rightHintIcon = findViewById(R.id.right_hint_icon)
            Log.d(TAG, "✅ Views initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing views", e)
            throw e
        }
    }
    
    private fun setupGestureDetector() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            
            override fun onDown(e: MotionEvent): Boolean {
                if (isActionTaken) return false
                currentSwipeDirection = 0
                Log.d(TAG, "👆 Touch down at X: ${e.x}")
                return true
            }
            
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isActionTaken || e1 == null) return false
                
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                
                // اگر حرکت افقی بیشتر از عمودی است
                if (abs(diffX) > abs(diffY) && abs(diffX) > 20) {
                    swipeProgress = diffX / rootLayout.width
                    
                    if (diffX > 0) {
                        currentSwipeDirection = 2 // راست
                        showRightSwipeIndicator(minOf(abs(swipeProgress), 1f))
                        Log.d(TAG, "→ Scrolling right: ${String.format("%.2f", swipeProgress * 100)}%")
                    } else {
                        currentSwipeDirection = 1 // چپ
                        showLeftSwipeIndicator(minOf(abs(swipeProgress), 1f))
                        Log.d(TAG, "← Scrolling left: ${String.format("%.2f", abs(swipeProgress) * 100)}%")
                    }
                    return true
                }
                return false
            }
            
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (isActionTaken || e1 == null) return false
                
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                
                Log.d(TAG, "🎯 Fling detected - diffX: $diffX, velocityX: $velocityX")
                
                // بررسی که سوایپ افقی است
                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > MIN_SWIPE_DISTANCE && abs(velocityX) > MIN_SWIPE_VELOCITY) {
                        if (diffX > 0) {
                            Log.d(TAG, "👉 SWIPE RIGHT DETECTED - Dismissing")
                            onSwipeRight()
                            return true
                        } else {
                            Log.d(TAG, "👈 SWIPE LEFT DETECTED - Snoozing")
                            onSwipeLeft()
                            return true
                        }
                    }
                }
                return false
            }
        })
    }
    
    private fun showRightSwipeIndicator(progress: Float) {
        try {
            if (progress > 0.1) {
                rightSwipeHint.visibility = View.VISIBLE
                rightSwipeHint.alpha = minOf(progress * 2, 1f)
                rightIcon.visibility = View.VISIBLE
                rightIcon.alpha = minOf(progress * 2, 1f)
            } else {
                rightSwipeHint.visibility = View.GONE
                rightIcon.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing right indicator", e)
        }
    }
    
    private fun showLeftSwipeIndicator(progress: Float) {
        try {
            if (progress > 0.1) {
                leftSwipeHint.visibility = View.VISIBLE
                leftSwipeHint.alpha = minOf(progress * 2, 1f)
                leftIcon.visibility = View.VISIBLE
                leftIcon.alpha = minOf(progress * 2, 1f)
            } else {
                leftSwipeHint.visibility = View.GONE
                leftIcon.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing left indicator", e)
        }
    }
    
    private fun setupUI() {
        val title = intent.getStringExtra("title") ?: "⏰ یادآوری"
        val description = intent.getStringExtra("description") ?: ""
        smartReminderId = intent.getStringExtra("smart_reminder_id")
        
        Log.d(TAG, "📝 Title: $title, SmartID: $smartReminderId")
        
        try {
            findViewById<TextView>(R.id.alarm_title)?.text = title
            findViewById<TextView>(R.id.alarm_description)?.apply {
                text = description
                visibility = if (description.isNotEmpty()) View.VISIBLE else View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up UI", e)
        }
        setupHandleDrag()
        startHandlePulse()
    }

    /**
     * درگ روی دستهٔ مرکزی برای سوایپ چپ/راست (مشابه ساعت اندروید)
     */
    private fun setupHandleDrag() {
        var handleDownX = 0f
        swipeHandle.setOnTouchListener { v, event ->
            if (isActionTaken) return@setOnTouchListener true

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleDownX = event.rawX
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    Log.d(TAG, "🎯 Handle DOWN x=${event.x}")
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffX = event.rawX - handleDownX
                    swipeProgress = diffX / rootLayout.width

                    // محدودسازی جابه‌جایی برای حس بهتر
                    val clamped = diffX.coerceIn(-rootLayout.width * 0.45f, rootLayout.width * 0.45f)
                    v.translationX = clamped
                    swipeHandleGlow.translationX = clamped * 0.4f

                    if (diffX > 0) {
                        showRightSwipeIndicator(minOf(abs(swipeProgress), 1f))
                        rightHintIcon.alpha = 0.6f
                        leftHintIcon.alpha = 0.15f
                    } else {
                        showLeftSwipeIndicator(minOf(abs(swipeProgress), 1f))
                        leftHintIcon.alpha = 0.6f
                        rightHintIcon.alpha = 0.15f
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val diffX = event.rawX - handleDownX
                    Log.d(TAG, "🏁 Handle UP diffX=$diffX")

                    val threshold = rootLayout.width * 0.25f
                    if (!isActionTaken && abs(diffX) > threshold) {
                        if (diffX > 0) {
                            Log.d(TAG, "👉 Handle drag right -> done")
                            onSwipeRight()
                        } else {
                            Log.d(TAG, "👈 Handle drag left -> snooze")
                            onSwipeLeft()
                        }
                    } else {
                        // برگرداندن به مرکز
                        v.animate().translationX(0f).setDuration(150).start()
                        swipeHandleGlow.animate().translationX(0f).setDuration(150).start()
                        leftSwipeHint.visibility = View.GONE
                        rightSwipeHint.visibility = View.GONE
                        leftIcon.visibility = View.GONE
                        rightIcon.visibility = View.GONE
                        leftHintIcon.alpha = 0.15f
                        rightHintIcon.alpha = 0.15f
                    }
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * انیمیشن ضربان/نور برای آیکون مرکزی و هالهٔ اطراف
     */
    private fun startHandlePulse() {
        try {
            val scaleUpX = ObjectAnimator.ofFloat(swipeHandle, View.SCALE_X, 1f, 1.08f)
            val scaleUpY = ObjectAnimator.ofFloat(swipeHandle, View.SCALE_Y, 1f, 1.08f)
            val scaleDownX = ObjectAnimator.ofFloat(swipeHandle, View.SCALE_X, 1.08f, 1f)
            val scaleDownY = ObjectAnimator.ofFloat(swipeHandle, View.SCALE_Y, 1.08f, 1f)

            val glowPulse = ValueAnimator.ofFloat(0.0f, 0.35f, 0.0f).apply {
                duration = 2000
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { anim ->
                    swipeHandleGlow.alpha = anim.animatedValue as Float
                }
            }

            val set = AnimatorSet().apply {
                play(scaleUpX).with(scaleUpY)
                play(scaleDownX).with(scaleDownY).after(scaleUpX)
                duration = 1200
                interpolator = AccelerateDecelerateInterpolator()
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!isActionTaken) {
                            start()
                        }
                    }
                })
            }

            set.start()
            glowPulse.start()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting handle pulse", e)
        }
    }
    
    private fun showSwipeHints() {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val animator = ObjectAnimator.ofFloat(rootLayout, "translationX", 0f, 30f, 0f, -30f, 0f)
                animator.duration = 3000
                animator.interpolator = AccelerateDecelerateInterpolator()
                animator.repeatCount = 2
                animator.start()
                
                Log.d(TAG, "✅ Swipe hints animation started")
            } catch (e: Exception) {
                Log.e(TAG, "Error showing swipe hints", e)
            }
        }, 1500)
    }
    
    private fun onSwipeRight() {
        if (isActionTaken) return
        isActionTaken = true
        
        Log.d(TAG, "✅ User dismissed - Swipe Right")
        cancelAlarmNotification()
        
        try {
            val animator = ObjectAnimator.ofFloat(rootLayout, "translationX", 0f, rootLayout.width.toFloat())
            animator.duration = 500
            animator.interpolator = AccelerateDecelerateInterpolator()
            animator.start()
            
            markAsDone()
            
            Handler(Looper.getMainLooper()).postDelayed({
                stopAlarm()
                finish()
            }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onSwipeRight", e)
            finish()
        }
    }
    
    private fun onSwipeLeft() {
        if (isActionTaken) return
        isActionTaken = true
        
        Log.d(TAG, "⏰ User snoozed - Swipe Left")
        cancelAlarmNotification()
        
        try {
            val animator = ObjectAnimator.ofFloat(rootLayout, "translationX", 0f, -rootLayout.width.toFloat())
            animator.duration = 500
            animator.interpolator = AccelerateDecelerateInterpolator()
            animator.start()
            
            snoozeReminder()
            
            Handler(Looper.getMainLooper()).postDelayed({
                stopAlarm()
                finish()
            }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onSwipeLeft", e)
            finish()
        }
    }
    
    /**
     * پخش یادآوری هوشمند با TTS (متن به گفتار)
     * برای یادآوری‌هایی که با هوش مصنوعی تولید شده‌اند
     * اولویت: GapGPT (آنلاین) → Android TTS (آفلاین)
     */
    private fun startSmartReminderTTS() {
        try {
            Log.d(TAG, "🔊 Starting smart reminder TTS")
            val title = intent.getStringExtra("title") ?: "⏰ یادآوری"
            val description = intent.getStringExtra("description") ?: ""

            // تنظیم volume به حداکثر
            try {
                val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
                audioManager?.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                    AudioManager.FLAG_SHOW_UI
                )
            } catch (_: Exception) {}

            speechScope.launch {
                val ttsText = SmartReminderSpeechHelper.generateSpeechText(this@FullScreenAlarmActivity, title, description)
                Log.d(TAG, "✅ Smart speech text ready: $ttsText")

                val helper = TTSHelper(this@FullScreenAlarmActivity)
                ttsHelper = helper
                helper.initialize {
                    speechScope.launch {
                        Log.d(TAG, "🔊 Speaking smart reminder (online-first): $ttsText")
                        helper.speakOnlineFirst(ttsText)
                    }
                }
            }

            // اگر TTS آماده نشد، بعد از 8 ثانیه آلارم معمولی پخش شود
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isActionTaken) {
                    Log.d(TAG, "⏰ TTS timeout or done, starting alarm effects")
                    startAlarmSound()
                    startVibration()
                }
            }, 8000)

            Log.d(TAG, "✅ Smart reminder TTS initiated")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting smart reminder TTS", e)
            startAlarmEffects()
        }
    }

    private fun startAlarmEffects() {
        startAlarmSound()
        startVibration()
    }

    private fun cancelAlarmNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(9001)
            Log.d(TAG, "🔕 Notification 9001 cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cancelling notification", e)
        }
    }
    
    private fun startAlarmSound() {
        try {
            Log.d(TAG, "🔊 Starting alarm sound")
            
            val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                
                if (currentVolume < maxVolume * 0.8) {
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        (maxVolume * 0.8).toInt(),
                        AudioManager.FLAG_SHOW_UI
                    )
                }
            }
            
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            
            mediaPlayer = MediaPlayer().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()
                    setAudioAttributes(audioAttributes)
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_ALARM)
                }
                
                setDataSource(this@FullScreenAlarmActivity, alarmUri)
                isLooping = true
                setVolume(1.0f, 1.0f)
                
                setOnPreparedListener {
                    it.start()
                    Log.d(TAG, "✅ Alarm sound started")
                }
                
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    false
                }
                
                prepareAsync()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting alarm sound", e)
        }
    }
    
    private fun startVibration() {
        try {
            Log.d(TAG, "📳 Starting vibration")
            
            vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                val pattern = longArrayOf(0, 1500, 500, 1500, 500, 1500)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(pattern, 0)
                    vibrator?.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
                Log.d(TAG, "✅ Vibration started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting vibration", e)
        }
    }
    
    private fun markAsDone() {
        if (smartReminderId != null) {
            try {
                Log.d(TAG, "✅ Marking as done: $smartReminderId")
                val mgr = SmartReminderManager(this)
                mgr.completeReminder(smartReminderId!!)
                saveActionToFile("completed", smartReminderId!!)
                Log.d(TAG, "✅ Action persisted to file")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error marking as done", e)
            }
        }
    }
    
    private fun snoozeReminder() {
        if (smartReminderId != null) {
            try {
                Log.d(TAG, "⏰ Snoozing: $smartReminderId")
                val mgr = SmartReminderManager(this)
                mgr.snoozeReminder(smartReminderId!!, 5)
                saveActionToFile("snoozed", smartReminderId!!)
                Log.d(TAG, "✅ Snooze action persisted to file")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error snoozing", e)
            }
        }
    }
    
    private fun saveActionToFile(action: String, reminderId: String) {
        try {
            val logDir = getDir("reminder_logs", Context.MODE_PRIVATE)
            val logFile = java.io.File(logDir, "alarm_actions.log")
            
            val timestamp = System.currentTimeMillis()
            val logEntry = "$timestamp|$reminderId|$action|${Thread.currentThread().name}\n"
            
            logFile.appendText(logEntry)
            Log.d(TAG, "💾 Saved to file: action=$action for $reminderId")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving to file", e)
        }
    }
    
    private fun stopAlarm() {
        try {
            Log.d(TAG, "🛑 Stopping alarm")
            
            mediaPlayer?.apply {
                try {
                    if (isPlaying) {
                        stop()
                    }
                    release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping media player", e)
                }
            }
            mediaPlayer = null
            
            try {
                vibrator?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error canceling vibration", e)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping alarm", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔚 onDestroy")
        cancelAlarmNotification()
        stopAlarm()
        // پاکسازی TTS
        try {
            ttsHelper?.shutdown()
        } catch (_: Exception) {}
    }
    
    override fun onBackPressed() {
        Log.d(TAG, "🚫 Back button blocked")
    }
    
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        try {
            gestureDetector.onTouchEvent(ev)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in dispatchTouchEvent", e)
        }
        return super.dispatchTouchEvent(ev)
    }
}