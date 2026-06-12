package com.persianai.assistant.services

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import com.persianai.assistant.core.AIIntentController
import com.persianai.assistant.core.AIIntentRequest
import com.persianai.assistant.utils.PreferencesManager
import com.persianai.assistant.tts.BeepFallback
import com.persianai.assistant.stt.OnlineSTTService

/**
 * Voice Conversation Manager - Complete voice-to-voice AI assistant
 * 
 * Features:
 * - Voice-to-Voice conversations (speak → AI responds with voice)
 * - Real-time speech processing
 * - Multi-language TTS support (Android TTS + Online TTS)
 * - Conversation memory and context
 * - Voice activity detection
 * - Background conversation capability
 */
class VoiceConversationManager(
    private val context: Context,
    private val voiceEngine: UnifiedVoiceEngine,
    private val aiClient: com.persianai.assistant.ai.AIClient? = null
) {
    
    private val TAG = "VoiceConversation"
    
    // TTS Engine
    private var textToSpeech: TextToSpeech? = null
    
    // Conversation state
    private var isConversationActive = false
    private var conversationHistory = mutableListOf<ConversationMessage>()
    private var currentLanguage = "fa" // Persian default
    private var voiceMode = VoiceMode.HYBRID
    
    // Voice activity detection
    private var amplitudeThreshold = 1000
    private var lastVoiceTime = 0L

    private val onlineSTT by lazy { OnlineSTTService(context) }
    
    // Callbacks
    private var conversationListener: ConversationListener? = null
    
    enum class VoiceMode {
        ONLINE_ONLY,     // Only AI APIs
        HYBRID,          // Combination of both
        VOICE_ONLY       // Voice-to-voice only
    }
    
    data class ConversationMessage(
        val role: String, // "user" or "assistant"
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val audioFile: File? = null
    )
    
    interface ConversationListener {
        fun onConversationStarted()
        fun onConversationEnded()
        fun onUserSpeakingStarted()
        fun onUserSpeakingStopped()
        fun onAIThinking()
        fun onAIResponding(aiResponse: String)
        fun onAIResponseSpoken()
        fun onError(error: String)
    }
    
    /**
     * Initialize conversation system
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "🔧 Initializing voice conversation system...")
            
            // Initialize TTS
            initializeTTS()
            
            // Load conversation history
            loadConversationHistory()
            
            Log.d(TAG, "✅ Voice conversation system initialized")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing conversation system", e)
            conversationListener?.onError("خطا در راه‌اندازی سیستم مکالمه: ${e.message}")
            false
        }
    }

    // Removed Haaniye MediaPlayer helper (offline TTS removed)

    private suspend fun speakWithAndroidOrBeep(text: String) = withContext(Dispatchers.Main) {
        if (text.isBlank()) return@withContext
        try {
            speakWithAndroidTTS(text)
            return@withContext
        } catch (_: Exception) {}

        try {
            BeepFallback.beep()
        } catch (_: Exception) {
        }
    }
    
    /**
     * Start voice conversation mode
     */
    suspend fun startConversation(): Boolean = withContext(Dispatchers.Main) {
        try {
            if (isConversationActive) {
                Log.w(TAG, "Conversation already active")
                return@withContext true
            }
            
            Log.d(TAG, "🎤 Starting voice conversation...")
            
            // Check permissions
            if (!voiceEngine.hasRequiredPermissions()) {
                conversationListener?.onError("دسترسی میکروفن لازم است")
                return@withContext false
            }
            
            isConversationActive = true
            conversationListener?.onConversationStarted()
            
            // Start listening for user input
            startListeningLoop()
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting conversation", e)
            conversationListener?.onError("خطا در شروع مکالمه: ${e.message}")
            false
        }
    }
    
    /**
     * Stop conversation mode
     */
    suspend fun stopConversation(): Boolean = withContext(Dispatchers.Main) {
        try {
            if (!isConversationActive) return@withContext true
            
            Log.d(TAG, "🛑 Stopping voice conversation...")
            
            isConversationActive = false
            conversationListener?.onConversationEnded()
            
            // Save conversation history
            saveConversationHistory()
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping conversation", e)
            false
        }
    }
    
    /**
     * Main conversation loop
     */
    private suspend fun startListeningLoop() = withContext(Dispatchers.Main) {
        while (isConversationActive) {
            try {
                // Listen for voice input
                conversationListener?.onUserSpeakingStarted()
                
                val recordingResult = recordUserInput()
                if (recordingResult == null) {
                    continue
                }
                
                // Process the user's speech
                val userText = processUserSpeech(recordingResult.file)
                if (userText.isBlank()) {
                    try {
                        conversationListener?.onError("متوجه نشدم. لطفاً دوباره بگویید.")
                    } catch (_: Exception) {
                    }

                    // In offline voice conversation we must not get stuck in silence.
                    try {
                        speakWithAndroidOrBeep("متوجه نشدم، دوباره بگو")
                    } catch (_: Exception) {
                    }
                    continue
                }

                try {
                    val controller = AIIntentController(context)
                    val intent = controller.detectIntentFromTextAsync(userText)
                    Log.d(TAG, "AIIntent: ${intent.name}")
                } catch (_: Exception) {
                }
                
                // Add to conversation history
                addToConversation("user", userText)
                
                // Get AI response
                conversationListener?.onAIThinking()
                val aiResult = getAIResponse(userText)

                // Speak the response
                conversationListener?.onAIResponding(aiResult.text)
                val toSpeak = aiResult.spokenOutput?.takeIf { it.isNotBlank() } ?: aiResult.text
                speakResponse(toSpeak)

                // Add AI response to history
                addToConversation("assistant", aiResult.text)
                
                conversationListener?.onAIResponseSpoken()
                
                // Small pause before listening again
                delay(1000)
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in conversation loop", e)
                conversationListener?.onError("خطا در مکالمه: ${e.message}")
            }
        }
    }
    
    /**
     * Record user voice input
     */
    private suspend fun recordUserInput(): RecordingResult? = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "🎤 Recording user input...")
            
            // Start recording
            val startResult = voiceEngine.startRecording()
            if (!startResult.isSuccess) {
                Log.e(TAG, "Failed to start recording")
                return@withContext null
            }
            
            // Listen for voice activity
            var hasSpeech = false
            val maxRecordingTime = 45000L // allow longer phrases
            val silenceStopMs = 2500L // wait longer before cutting off
            val startTime = System.currentTimeMillis()
            lastVoiceTime = 0L
            
            val amplitudeJob = launch {
                while (isRecording()) {
                    val amplitude = voiceEngine.getCurrentAmplitude()
                    if (amplitude > amplitudeThreshold) {
                        hasSpeech = true
                        lastVoiceTime = System.currentTimeMillis()
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > maxRecordingTime) break

                    if (hasSpeech && lastVoiceTime > 0L) {
                        val silentFor = System.currentTimeMillis() - lastVoiceTime
                        if (silentFor > silenceStopMs) break
                    }

                    delay(100)
                }
            }
            
            // Wait for speech or timeout
            while (isRecording() && !hasSpeech && (System.currentTimeMillis() - startTime) < maxRecordingTime) {
                delay(100)
            }
            
            // Stop recording
            val stopResult = voiceEngine.stopRecording()
            amplitudeJob.cancel()
            if (stopResult.isSuccess) {
                try {
                    stopResult.getOrThrow()
                } catch (e: Exception) {
                    Log.e(TAG, "Error retrieving recording result", e)
                    null
                }
            } else {
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error recording user input", e)
            null
        }
    }
    
    /**
     * Process user's speech using hybrid analysis
     */
    private suspend fun processUserSpeech(audioFile: File): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Processing user speech...")

            // Offline-first with online fallback - now using OnlineSTTService
            val sttResult = onlineSTT.transcribeAudio(audioFile)
            val text = if (sttResult.isSuccess) sttResult.text else ""
            if (text.isNotBlank()) {
                Log.d(TAG, "✅ Speech processed: $text")
                return@withContext text
            }

            Log.e(TAG, "Failed to process speech")
            ""
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing speech", e)
            ""
        }
    }
    
    /**
     * Get AI response based on conversation context.
     *
     * Policy:
     * - OFFLINE: only offline processing
     * - HYBRID: simple intents offline; complex intents online when available
     * - ONLINE: online when available; otherwise fallback offline
     */
    private suspend fun getAIResponse(userInput: String): com.persianai.assistant.core.AIIntentResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🤖 Getting AI response for: $userInput")

            val controller = AIIntentController(context)
            val intent = controller.detectIntentFromTextAsync(userInput)
            controller.handle(
                AIIntentRequest(
                    intent = intent,
                    source = AIIntentRequest.Source.VOICE,
                    workingModeName = PreferencesManager(context).getWorkingMode().name
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting AI response", e)
            com.persianai.assistant.core.AIIntentResult(
                text = "متاسفانه مشکلی پیش آمده. می‌توانید دوباره تلاش کنید؟",
                intentName = "error",
                success = false,
                spokenOutput = null
            )
        }
    }

    /**
     * Speak AI response using appropriate TTS
     */
    private suspend fun speakResponse(response: String) = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "🔊 Speaking response: $response")

            // Audio output preference: Android TTS -> beep.
            speakWithAndroidOrBeep(response)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error speaking response", e)
        }
    }
    
    /**
     * Speak using Haaniye TTS model
     */
    // Offline Haaniye TTS removed to reduce APK size and dependency surface.
    
    /**
     * Speak using Android TextToSpeech
     */
    private suspend fun speakWithAndroidTTS(text: String) = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "📱 Using Android TTS for: $text")
            
            textToSpeech?.let { tts ->
                tts.language = when (currentLanguage) {
                    "fa" -> Locale("fa", "IR")
                    "en" -> Locale.ENGLISH
                    else -> Locale.getDefault()
                }
                
                // Split long text into smaller chunks
                val maxLength = 100
                val words = text.split(" ")
                var currentText = ""
                
                for (word in words) {
                    if (currentText.length + word.length + 1 > maxLength) {
                        tts.speak(currentText, TextToSpeech.QUEUE_FLUSH, null, "chunk_${System.currentTimeMillis()}")
                        delay(100)
                        currentText = word
                    } else {
                        currentText += if (currentText.isEmpty()) word else " $word"
                    }
                }
                
                if (currentText.isNotEmpty()) {
                    tts.speak(currentText, TextToSpeech.QUEUE_FLUSH, null, "final_chunk_${System.currentTimeMillis()}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error with Android TTS", e)
            throw e
        }
    }
    
    /**
     * Initialize TTS engines
     */
    private suspend fun initializeTTS() = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "🔧 Initializing TTS engines...")
            
            // Initialize Android TTS
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "✅ Android TTS initialized")
                    textToSpeech?.language = Locale("fa", "IR")
                } else {
                    Log.e(TAG, "❌ Android TTS initialization failed")
                }
            }

            // Haaniye offline TTS removed; relying on Android TTS and online providers
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing TTS", e)
            throw e
        }
    }
    
    /**
     * Build conversation context for AI
     */
    private fun buildConversationContext(): String {
        val recentHistory = conversationHistory.takeLast(10)
        return recentHistory.joinToString("\n") { message ->
            "${if (message.role == "user") "کاربر" else "دستیار"}: ${message.content}"
        }
    }
    
    /**
     * Add message to conversation history
     */
    private fun addToConversation(role: String, content: String) {
        conversationHistory.add(ConversationMessage(role, content))
        
        // Keep history manageable (last 50 messages)
        if (conversationHistory.size > 50) {
            conversationHistory.removeAt(0)
        }
    }
    
    /**
     * Load conversation history from storage
     */
    private suspend fun loadConversationHistory() = withContext(Dispatchers.IO) {
        try {
            // TODO: Load from SharedPreferences or database
            Log.d(TAG, "📂 Loading conversation history...")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading conversation history", e)
        }
    }
    
    /**
     * Save conversation history to storage
     */
    private suspend fun saveConversationHistory() = withContext(Dispatchers.IO) {
        try {
            // TODO: Save to SharedPreferences or database
            Log.d(TAG, "💾 Saving conversation history...")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving conversation history", e)
        }
    }
    
    /**
     * Get current recording status
     */
    private fun isRecording(): Boolean {
        return try {
            voiceEngine.isRecordingInProgress()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking recording status", e)
            false
        }
    }
    
    /**
     * Set conversation listener
     */
    fun setConversationListener(listener: ConversationListener) {
        this.conversationListener = listener
    }
    
    /**
     * Set voice mode
     */
    fun setVoiceMode(mode: VoiceMode) {
        this.voiceMode = mode
        Log.d(TAG, "Voice mode set to: $mode")
    }
    
    /**
     * Set language
     */
    fun setLanguage(language: String) {
        this.currentLanguage = language
        Log.d(TAG, "Language set to: $language")
    }
}
