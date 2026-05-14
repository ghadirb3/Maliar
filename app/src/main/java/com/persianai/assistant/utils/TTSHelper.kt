package com.persianai.assistant.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.media.MediaPlayer
import java.io.File
import java.util.Locale
import java.util.UUID
import com.persianai.assistant.config.RemoteAIConfigManager
import com.persianai.assistant.tts.GapGPTTTS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * TTS Helper with online-first strategy
 * Priority chain: GapGPT (online) → Android TTS (fallback)
 */
class TTSHelper(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val prefsManager = PreferencesManager(context)
    private val remoteConfigManager = RemoteAIConfigManager.getInstance(context)
    private val gapgptTTS = GapGPTTTS(context)
    
    // For waiting on Android TTS completion
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    companion object {
        private const val TAG = "TTSHelper"

        fun normalizeDigits(input: String): String {
            val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
            val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
            val latinDigits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

            val result = StringBuilder()
            for (char in input) {
                val persianIndex = persianDigits.indexOf(char)
                if (persianIndex != -1) {
                    result.append(latinDigits[persianIndex])
                    continue
                }
                val arabicIndex = arabicDigits.indexOf(char)
                if (arabicIndex != -1) {
                    result.append(latinDigits[arabicIndex])
                    continue
                }
                result.append(char)
            }
            return result.toString()
        }

        fun formatPhoneNumberForTTS(phoneNumber: String): String {
            val normalized = normalizeDigits(phoneNumber)
            val digitsOnly = normalized.replace(Regex("[^0-9+]"), "")
            val withSpaces = digitsOnly.chunked(1).joinToString(" ")
            return "\u200E$withSpaces"
        }
    }

    fun initialize(onReady: (() -> Unit)? = null) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("fa", "IR"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Persian language not supported, disabling Android TTS")
                    tts?.shutdown()
                    tts = null
                    isInitialized = false
                } else {
                    tts?.setPitch(1.0f)
                    tts?.setSpeechRate(0.9f)
                    isInitialized = true
                    Log.d(TAG, "Android TTS initialized successfully")
                    
                    // Register utterance listener for waiting support
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.d(TAG, "TTS started: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            Log.d(TAG, "TTS completed: $utteranceId")
                            utteranceId?.let {
                                pendingUtterances.remove(it)?.complete(Unit)
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            Log.e(TAG, "TTS error: $utteranceId")
                            utteranceId?.let {
                                pendingUtterances.remove(it)?.completeExceptionally(
                                    Exception("TTS error")
                                )
                            }
                        }
                    })
                }
                onReady?.invoke()
            } else {
                Log.e(TAG, "Android TTS initialization failed")
                isInitialized = false
                tts = null
            }
        }
    }

    /**
     * Speak with online-first strategy and wait for completion
     * Priority: GapGPT → Android TTS
     */
    suspend fun speakOnlineFirstAndWait(text: String, timeoutMs: Long = 20_000L) {
        if (!prefsManager.isTTSEnabled()) {
            Log.d(TAG, "TTS disabled in preferences")
            return
        }

        val cleanText = cleanTextForTTS(text)
        if (cleanText.isBlank()) {
            Log.d(TAG, "Empty text after cleaning, skipping TTS")
            return
        }

        Log.d(TAG, "Speaking with wait: $cleanText")

        // Get provider priority from remote config
        val providers = remoteConfigManager.getTTSPriority()
        Log.d(TAG, "TTS provider priority: $providers")

        // Try each provider in order
        for (provider in providers) {
            when (provider.lowercase()) {
                "gapgpt" -> {
                    try {
                        Log.d(TAG, "Trying GapGPT TTS...")
                        val audioFile = gapgptTTS.synthesizeSpeech(cleanText)
                        if (audioFile != null && audioFile.exists()) {
                            Log.d(TAG, "GapGPT TTS succeeded, playing audio")
                            playAudioFileAndWait(audioFile, timeoutMs)
                            return
                        }
                        Log.w(TAG, "GapGPT TTS failed or returned null")
                    } catch (e: Exception) {
                        Log.e(TAG, "GapGPT TTS error: ${e.message}")
                    }
                }
                "android" -> {
                    // Will be handled in final fallback
                    continue
                }
                else -> {
                    Log.d(TAG, "Unknown provider: $provider, skipping")
                }
            }
        }

        // Final fallback: Android TTS with waiting
        if (isInitialized && tts != null) {
            Log.d(TAG, "Falling back to Android TTS with wait")
            try {
                val utteranceId = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<Unit>()
                pendingUtterances[utteranceId] = deferred

                withContext(Dispatchers.Main) {
                    val params = hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to utteranceId)
                    tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params)
                }

                // Wait for completion or timeout
                withTimeoutOrNull(timeoutMs) {
                    deferred.await()
                } ?: run {
                    Log.w(TAG, "Android TTS wait timeout")
                    pendingUtterances.remove(utteranceId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Android TTS error: ${e.message}")
            }
        } else {
            Log.w(TAG, "Android TTS not available")
        }
    }

    /**
     * Speak with online-first strategy (non-blocking)
     * Priority: GapGPT → Android TTS
     */
    suspend fun speakOnlineFirst(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!prefsManager.isTTSEnabled()) {
            Log.d(TAG, "TTS disabled in preferences")
            return
        }

        val cleanText = cleanTextForTTS(text)
        if (cleanText.isBlank()) {
            Log.d(TAG, "Empty text after cleaning, skipping TTS")
            return
        }

        Log.d(TAG, "Speaking (non-blocking): $cleanText")

        // Get provider priority from remote config
        val providers = remoteConfigManager.getTTSPriority()
        Log.d(TAG, "TTS provider priority: $providers")

        // Try each provider in order
        for (provider in providers) {
            when (provider.lowercase()) {
                "gapgpt" -> {
                    try {
                        Log.d(TAG, "Trying GapGPT TTS...")
                        val audioFile = gapgptTTS.synthesizeSpeech(cleanText)
                        if (audioFile != null && audioFile.exists()) {
                            Log.d(TAG, "GapGPT TTS succeeded, playing audio")
                            playAudioFile(audioFile)
                            return
                        }
                        Log.w(TAG, "GapGPT TTS failed or returned null")
                    } catch (e: Exception) {
                        Log.e(TAG, "GapGPT TTS error: ${e.message}")
                    }
                }
                "android" -> {
                    // Will be handled in final fallback
                    continue
                }
                else -> {
                    Log.d(TAG, "Unknown provider: $provider, skipping")
                }
            }
        }

        // Final fallback: Android TTS
        if (isInitialized && tts != null) {
            Log.d(TAG, "Falling back to Android TTS")
            runOnUiThread {
                tts?.speak(cleanText, queueMode, null)
            }
        } else {
            Log.w(TAG, "Android TTS not available")
        }
    }

    /**
     * Legacy speak method (direct Android TTS)
     * For backward compatibility
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!prefsManager.isTTSEnabled()) {
            Log.d(TAG, "TTS disabled in preferences")
            return
        }

        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized")
            return
        }

        val cleanText = cleanTextForTTS(text)
        if (cleanText.isBlank()) {
            Log.d(TAG, "Empty text after cleaning, skipping TTS")
            return
        }

        Log.d(TAG, "Speaking (legacy): $cleanText")

        // Direct Android TTS
        runOnUiThread {
            tts?.speak(cleanText, queueMode, null)
        }
    }

    /**
     * Play audio file (non-blocking)
     */
    private fun playAudioFile(audioFile: File) {
        try {
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(audioFile.absolutePath)
            mediaPlayer.setOnCompletionListener {
                it.release()
                audioFile.delete()
                Log.d(TAG, "Audio playback completed and file deleted")
            }
            mediaPlayer.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                mp.release()
                audioFile.delete()
                true
            }
            mediaPlayer.prepare()
            mediaPlayer.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio file: ${e.message}")
            audioFile.delete()
        }
    }

    /**
     * Play audio file and wait for completion
     */
    private suspend fun playAudioFileAndWait(audioFile: File, timeoutMs: Long) {
        withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<Unit>()
            try {
                val mediaPlayer = MediaPlayer()
                mediaPlayer.setDataSource(audioFile.absolutePath)
                mediaPlayer.setOnCompletionListener {
                    it.release()
                    audioFile.delete()
                    Log.d(TAG, "Audio playback completed and file deleted")
                    deferred.complete(Unit)
                }
                mediaPlayer.setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    mp.release()
                    audioFile.delete()
                    deferred.completeExceptionally(Exception("MediaPlayer error"))
                    true
                }
                mediaPlayer.prepare()
                mediaPlayer.start()

                // Wait for completion or timeout
                withTimeoutOrNull(timeoutMs) {
                    deferred.await()
                } ?: run {
                    Log.w(TAG, "Audio playback timeout")
                    mediaPlayer.stop()
                    mediaPlayer.release()
                    audioFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio file: ${e.message}")
                audioFile.delete()
                deferred.completeExceptionally(e)
            }
        }
    }

    fun cleanup() {
        gapgptTTS.cleanupOldAudioFiles()
    }

    private fun runOnUiThread(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(action)
        }
    }

    fun stop() {
        tts?.stop()
        pendingUtterances.clear()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    fun isAvailable(): Boolean {
        return isInitialized && tts != null
    }

    /**
     * Clean text for TTS: remove emoji, links, special chars
     */
    private fun cleanTextForTTS(text: String): String {
        return text
            .replace(Regex("[\\p{So}\\p{Sk}]"), "") // Remove emoji and symbols
            .replace(Regex("https?://\\S+"), "") // Remove URLs
            .replace(Regex("[*_~`#]"), "") // Remove markdown chars
            .replace(Regex("\\n{3,}"), "\n\n") // Max 2 newlines
            .replace(Regex("[ \\t]+"), " ") // Normalize spaces
            .trim()
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }
}
