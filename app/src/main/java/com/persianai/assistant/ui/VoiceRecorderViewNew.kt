package com.persianai.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.persianai.assistant.R
// removed duplicate imports of CoroutineScope/Dispatchers; keep Job/cancel if needed below
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * View ضبط صدا شبیه تلگرام
 * استفاده از HybridVoiceRecorder برای ضبط سالم و ایمن
 */
class VoiceRecorderViewNew @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    interface VoiceRecorderListener {
        fun onRecordingStarted()
        fun onRecordingCompleted(audioFile: File, durationMs: Long)
        fun onRecordingCancelled()
    }
    
    private var listener: VoiceRecorderListener? = null
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val helper = com.persianai.assistant.services.VoiceRecordingHelper(context)
    private val mainScope: CoroutineScope = MainScope()
    private var recordingStartTime = 0L
    private var isRecording = false
    private var isCancelled = false
    private var slideOffset = 0f
    private var amplitude = 0
    
    // UI Elements
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val micDrawable = ContextCompat.getDrawable(context, R.drawable.ic_mic)?.mutate()?.apply {
        setTint(Color.WHITE)
    }
    
    // Animation
    private var pulseAnimator: ValueAnimator? = null
    private var pulseRadius = 0f
    
    private val amplitudes = mutableListOf<Float>()
    private val maxAmplitudes = 50
    
    // Colors
    private val recordColor = Color.parseColor("#FF5252")
    private val cancelColor = Color.parseColor("#757575")
    private val textColor = Color.parseColor("#FFFFFF")
    private val waveformColor = Color.parseColor("#4CAF50")
    
    // Amplitude tracking removed - not provided by VoiceRecordingHelper
    
    init {
        textPaint.apply {
            color = textColor
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        
        waveformPaint.apply {
            color = waveformColor
            strokeWidth = 4f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        
        // Initialize helper
        helper.setListener(object : com.persianai.assistant.services.VoiceRecordingHelper.RecordingListener {
            override fun onRecordingStarted() {
                post { isRecording = true; recordingStartTime = System.currentTimeMillis(); listener?.onRecordingStarted(); invalidate() }
            }

            override fun onRecordingCompleted(audioFile: File, durationMs: Long) {
                post { isRecording = false; listener?.onRecordingCompleted(audioFile, durationMs); invalidate() }
            }

            override fun onRecordingCancelled() {
                post { isRecording = false; listener?.onRecordingCancelled(); invalidate() }
            }

            override fun onRecordingError(error: String) {
                post { isRecording = false; listener?.onRecordingCancelled(); invalidate() }
            }
        })
        
        startPulseAnimation()
    }
    
    fun setListener(listener: VoiceRecorderListener) {
        this.listener = listener
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val baseRadius = min(width, height) * 0.35f
        val pulseAdd = min(width, height) * 0.08f
        
        // Draw background circle with pulse effect
        paint.color = if (isCancelled) cancelColor else recordColor
        paint.alpha = if (isRecording) (120 + pulseRadius * 2f).toInt().coerceAtMost(255) else 240
        canvas.drawCircle(centerX - slideOffset, centerY, baseRadius + pulseRadius * pulseAdd, paint)
        
        // Draw mic icon
        micDrawable?.let { drawable ->
            val iconSize = baseRadius * 0.85f
            val left = (centerX - slideOffset - iconSize / 2).toInt()
            val top = (centerY - iconSize / 2).toInt()
            val right = (centerX - slideOffset + iconSize / 2).toInt()
            val bottom = (centerY + iconSize / 2).toInt()
            drawable.setBounds(left, top, right, bottom)
            drawable.draw(canvas)
        }
        
        if (isRecording) {
            // Draw waveform
            drawWaveform(canvas, centerX - slideOffset, centerY)
            
            // Draw recording time
            val duration = (System.currentTimeMillis() - recordingStartTime) / 1000
            val minutes = duration / 60
            val seconds = duration % 60
            val timeText = String.format("%02d:%02d", minutes, seconds)
            canvas.drawText(timeText, centerX - slideOffset, centerY - 100, textPaint)
            
            // Draw slide to cancel hint
            if (slideOffset < 100) {
                textPaint.alpha = (255 - slideOffset * 2.5f).toInt()
                textPaint.textSize = 30f
                canvas.drawText("⬅️ بکشید برای لغو", centerX + 150, centerY, textPaint)
                textPaint.alpha = 255
                textPaint.textSize = 40f
            }
            
            // Visual feedback for cancellation
            if (slideOffset > 150) {
                paint.color = cancelColor
                paint.alpha = 200
                canvas.drawCircle(centerX - slideOffset, centerY, baseRadius + 12f, paint)
                textPaint.color = Color.WHITE
                canvas.drawText("❌", centerX - slideOffset, centerY + 15, textPaint)
            }
        } else {
            // Idle hint
            textPaint.color = Color.WHITE
            textPaint.alpha = 210
            textPaint.textSize = 26f
            canvas.drawText("برای ضبط نگه دارید", centerX, centerY + baseRadius + 32f, textPaint)
            textPaint.textSize = 20f
            canvas.drawText("برای لغو به چپ بکشید", centerX, centerY + baseRadius + 64f, textPaint)
            textPaint.alpha = 255
            textPaint.textSize = 40f
        }
    }
    
    private fun drawWaveform(canvas: Canvas, centerX: Float, centerY: Float) {
        if (amplitudes.isEmpty()) return
        
        val path = Path()
        val waveWidth = 300f
        val waveHeight = 60f
        val startX = centerX - waveWidth / 2
        
        amplitudes.forEachIndexed { index, amplitude ->
            val x = startX + (index * waveWidth / maxAmplitudes)
            val y = centerY + (amplitude * waveHeight * (if (index % 2 == 0) 1 else -1))
            
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        
        waveformPaint.alpha = 150
        canvas.drawPath(path, waveformPaint)
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!isRecording) {
                    startRecording()
                }
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (isRecording) {
                    slideOffset = abs(width / 2f - event.x)
                    isCancelled = slideOffset > 150
                    invalidate()
                }
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isRecording) {
                    if (isCancelled || (System.currentTimeMillis() - recordingStartTime) < 500) {
                        cancelRecording()
                    } else {
                        stopRecording()
                    }
                }
                slideOffset = 0f
                isCancelled = false
                invalidate()
                return true
            }
        }
        return false
    }
    
    private fun startRecording() {
        try {
            mainScope.launch { helper.startRecording() }
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            
        } catch (e: Exception) {
            Log.e("VoiceRecorderViewNew", "Failed to start recording", e)
            listener?.onRecordingCancelled()
        }
    }
    
    private fun stopRecording() {
        if (!isRecording) return
        
        try {
            mainScope.launch { helper.stopRecording() }
        } catch (e: Exception) {
            Log.e("VoiceRecorderViewNew", "Failed to stop recording", e)
            listener?.onRecordingCancelled()
        } finally {
            isRecording = false
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
        }
    }
    
    private fun cancelRecording() {
        if (!isRecording) return
        
        try {
            mainScope.launch { helper.cancelRecording() }
        } catch (e: Exception) {
            Log.w("VoiceRecorderViewNew", "Error cancelling recording", e)
        } finally {
            isRecording = false
            listener?.onRecordingCancelled()
            
            // Haptic feedback for cancellation
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
        }
    }
    
    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                pulseRadius = animator.animatedValue as Float
                if (isRecording) {
                    invalidate()
                }
            }
            start()
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        if (isRecording) {
            cancelRecording()
        }
        coroutineScope.cancel()
    }
}
