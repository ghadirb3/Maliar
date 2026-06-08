package com.persianai.assistant.activities

import android.Manifest
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.persianai.assistant.adapters.ChatAdapter
import com.persianai.assistant.ai.AIClient
import com.persianai.assistant.models.AIModel
import com.persianai.assistant.models.AIProvider
import com.persianai.assistant.models.APIKey
import com.persianai.assistant.models.ChatMessage
import com.persianai.assistant.models.Conversation
import com.persianai.assistant.models.MessageRole
import com.persianai.assistant.ui.VoiceRecorderView
import com.persianai.assistant.utils.DefaultApiKeys
import com.persianai.assistant.utils.PreferencesManager
import com.persianai.assistant.utils.TTSHelper
import com.persianai.assistant.utils.PreferencesManager.ProviderPreference
import com.persianai.assistant.utils.AutoProvisioningManager
import com.persianai.assistant.integration.IviraIntegrationManager
import com.persianai.assistant.utils.IviraProcessingHelper
import com.persianai.assistant.services.VoiceRecordingHelper
import com.persianai.assistant.services.UnifiedVoiceEngine
import com.persianai.assistant.core.AIIntentController
import com.persianai.assistant.core.AIIntentRequest
import com.persianai.assistant.stt.OnlineSTTService
import com.persianai.assistant.ai.SimpleOfflineResponder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import android.util.Log
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import com.persianai.assistant.R

abstract class BaseChatActivity : AppCompatActivity() {

    protected lateinit var binding: ViewBinding
    protected lateinit var chatAdapter: ChatAdapter
    protected lateinit var prefsManager: PreferencesManager
    protected lateinit var ttsHelper: TTSHelper
    protected var aiClient: AIClient? = null
    protected var currentModel: AIModel = AIModel.TINY_LLAMA_OFFLINE
    protected val messages = mutableListOf<ChatMessage>()
    // protected lateinit var modelDownloadManager: ModelDownloadManager
    private lateinit var speechRecognizer: SpeechRecognizer
    private var voiceRecorderView: VoiceRecorderView? = null
    protected lateinit var voiceHelper: VoiceRecordingHelper
    private lateinit var conversationStorage: com.persianai.assistant.storage.ConversationStorage
    private lateinit var currentConversation: Conversation
    private var conversationLoaded: Boolean = false
    private var voiceConversationDialog: AlertDialog? = null
    private var voiceConversationJob: Job? = null
    
    companion object {
        protected const val CHAT_DISABLED = false
        private const val REQUEST_RECORD_AUDIO = 1001
        const val EXTRA_START_VOICE_CONVERSATION = "extra_start_voice_conversation"
        private const val MENU_ID_VOICE_CONVERSATION = 99001
    }
    private val httpClient = OkHttpClient()
    private val sttEngine by lazy { UnifiedVoiceEngine(this) }
    private val onlineSTT by lazy { OnlineSTTService(this) }
    private val hfApiKey: String by lazy {
        getSharedPreferences("api_keys", MODE_PRIVATE)
            .getString("hf_api_key", null)
            ?.takeIf { it.isNotBlank() }
            ?: DefaultApiKeys.getHuggingFaceKey()
            ?: ""
    }

    private fun chooseBestModel(apiKeys: List<APIKey>, pref: ProviderPreference): AIModel {
        // اولویت ثابت: ۱. Liara (gpt-5-nano) -> ۲. GAPGPT (gpt-5-nano) -> بدون فال‌بک به آفلاین
        // GAPGPT 400 می‌دهد، بنابراین LIARA اولویت اول است
        val activeProviders = apiKeys.filter { it.isActive }.map { it.provider }.toSet()
        
        android.util.Log.d("BaseChatActivity", "📊 Active providers: $activeProviders")
        android.util.Log.d("BaseChatActivity", "📊 All providers: ${apiKeys.map { it.provider }}")
        android.util.Log.d("BaseChatActivity", "📊 Active keys: ${apiKeys.filter { it.isActive }.map { "${it.provider}: ${it.key.take(10)}..." }}")
        
        // اولویت LIARA برای چت، سپس GAPGPT - بدون فال‌بک به آفلاین
        val selected = when {
            activeProviders.contains(com.persianai.assistant.models.AIProvider.LIARA) -> {
                android.util.Log.d("BaseChatActivity", "✅ استفاده از Liara: openai/gpt-5-nano")
                com.persianai.assistant.models.AIModel.LIARA_GPT_5_NANO
            }
            activeProviders.contains(com.persianai.assistant.models.AIProvider.GAPGPT) -> {
                android.util.Log.d("BaseChatActivity", "✅ استفاده از GAPGPT: gpt-5-nano")
                com.persianai.assistant.models.AIModel.GAPGPT_GPT_5_NANO
            }
            else -> {
                android.util.Log.e("BaseChatActivity", "❌ هیچ کلید آنلاین فعال نیست - خطا")
                throw IllegalStateException("هیچ کلید آنلاین فعال نیست. لطفاً کلیدهای API را تنظیم کنید.")
            }
        }

        android.util.Log.d("BaseChatActivity", "📊 Selected Model: ${selected.modelId}")
        // ذخیره مدل انتخاب شده در preferences
        prefsManager.saveSelectedModel(selected)
        return selected
    }
    
    // تابع کمکی برای فالینک به مدل بعدی GAPGPT در صورت خطا
    private fun getNextGAPGPTModel(currentModel: AIModel): AIModel {
        return when (currentModel) {
            com.persianai.assistant.models.AIModel.GAPGPT_GPT_5_NANO -> {
                android.util.Log.d("BaseChatActivity", "� فالینک به GAPGPT: gapgpt-deepseek-v3")
                com.persianai.assistant.models.AIModel.GAPGPT_GPT_4O_MINI
            }
            else -> currentModel
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (CHAT_DISABLED) {
            showChatDisabledMessage()
            return
        }
        
        // Initialize critical components first
        prefsManager = PreferencesManager(this)
        // modelDownloadManager = ModelDownloadManager(this)
        ttsHelper = TTSHelper(this)
        ttsHelper.initialize()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        
        android.util.Log.d("BaseChatActivity", "✅ Components initialized, prefsManager is ready")
        
        // Initialize conversation storage and load current conversation (if any)
        conversationStorage = com.persianai.assistant.storage.ConversationStorage(this)
        currentConversation = Conversation()
        val conversationScope = this@BaseChatActivity::class.java.simpleName
        lifecycleScope.launch {
            try {
                val id = conversationStorage.getCurrentConversationId(conversationScope)
                val loaded = if (!id.isNullOrBlank()) conversationStorage.getConversation(id) else null
                if (loaded != null) {
                    currentConversation = loaded
                    messages.clear()
                    messages.addAll(loaded.messages)
                    if (this@BaseChatActivity::chatAdapter.isInitialized) {
                        chatAdapter.notifyDataSetChanged()
                    }
                } else {
                    conversationStorage.setCurrentConversationId(conversationScope, currentConversation.id)
                    conversationStorage.saveConversation(currentConversation)
                }
                conversationLoaded = true
                maybeShowIntroMessage()
            } catch (e: Exception) {
                android.util.Log.w("BaseChatActivity", "Failed loading conversation: ${e.message}")
                conversationLoaded = true
                maybeShowIntroMessage()
            }
        }
        
        // Setup Voice Recording Helper
        voiceHelper = VoiceRecordingHelper(this)
        setupVoiceRecording()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        try {
            menuInflater.inflate(R.menu.assistant_menu, menu)
            if (menu.findItem(MENU_ID_VOICE_CONVERSATION) == null) {
                menu.add(Menu.NONE, MENU_ID_VOICE_CONVERSATION, Menu.NONE, "🎙️ مکالمه صوتی با مدل")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        } catch (_: Exception) {
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_ID_VOICE_CONVERSATION -> {
                startVoiceConversationDialog()
                true
            }
            R.id.action_calendar -> {
                startActivity(Intent(this, CalendarActivity::class.java))
                true
            }
            R.id.action_accounting -> {
                startActivity(Intent(this, AccountingAdvancedActivity::class.java))
                true
            }
            R.id.action_reminders -> {
                startActivity(Intent(this, AdvancedRemindersActivity::class.java))
                true
            }
            R.id.action_voice_nav -> {
                // startActivity(Intent(this, VoiceNavigationAssistantActivity::class.java))
                true
            }
            R.id.action_psychology -> {
                startActivity(Intent(this, PsychologyChatActivity::class.java))
                true
            }
            R.id.action_career -> {
                startActivity(Intent(this, CareerChatActivity::class.java))
                true
            }
            R.id.action_cultural -> {
                startActivity(Intent(this, CulturalChatActivity::class.java))
                true
            }
            R.id.action_refresh_keys -> {
                refreshAPIKeys()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    protected fun applyTranscriptToInput(text: String) {
        try {
            val edit = getMessageInput()
            edit.setText(text)
            edit.setSelection(edit.text?.length ?: 0)
            edit.requestFocus()
            getSendButton().isEnabled = true
            try {
                val warn = findViewById<android.widget.TextView?>(R.id.sttWarning)
                warn?.text = "تشخیص گفتار ممکن است خطا داشته باشد. در صورت نیاز، متن را اصلاح کنید."
                warn?.visibility = View.VISIBLE
            } catch (_: Exception) { }
        } catch (_: Exception) { }
    }

    protected fun handleTranscript(text: String) {
        applyTranscriptToInput(text)
        val mode = prefsManager.getRecordingMode()
        if (mode == PreferencesManager.RecordingMode.FAST) {
            try { sendMessage() } catch (_: Exception) { }
        }
    }

    private fun reuseUserMessage(message: ChatMessage) {
        if (message.role != MessageRole.USER) return
        applyTranscriptToInput(message.content)
    }

    protected abstract fun getRecyclerView(): androidx.recyclerview.widget.RecyclerView
    protected abstract fun getMessageInput(): com.google.android.material.textfield.TextInputEditText
    protected abstract fun getSendButton(): View
    protected abstract fun getVoiceButton(): View

    protected open fun setupChatUI() {
        // Ensure prefsManager is initialized before setting up AI client
        if (!::prefsManager.isInitialized) {
            android.util.Log.e("BaseChatActivity", "⚠️ prefsManager not initialized in setupChatUI!")
            return
        }
        setupRecyclerView()
        setupListeners()
        setupAIClient()
        maybeShowIntroMessage()
        maybeAutoStartVoiceConversation()
    }

    private fun maybeAutoStartVoiceConversation() {
        try {
            val shouldStart = intent?.getBooleanExtra(EXTRA_START_VOICE_CONVERSATION, false) == true
            if (!shouldStart) return
            intent?.removeExtra(EXTRA_START_VOICE_CONVERSATION)

            // Ensure UI is laid out
            try {
                getVoiceButton().post {
                    try {
                        startVoiceConversationDialog()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(messages) { message ->
            reuseUserMessage(message)
        }
        getRecyclerView().apply {
            layoutManager = LinearLayoutManager(this@BaseChatActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
        maybeShowIntroMessage()
    }

    private suspend fun transcribeWithHuggingFace(audioFile: File): String? = withContext(Dispatchers.IO) {
        if (hfApiKey.isBlank()) {
            Log.w("HF-STT", "HuggingFace key not set; skipping HF transcription")
            return@withContext null
        }
        return@withContext try {
            val bytes = audioFile.readBytes()
            val body = bytes.toRequestBody("audio/m4a".toMediaType())
            val request = Request.Builder()
                .url("https://api-inference.huggingface.co/models/openai/whisper-large-v3")
                .addHeader("Authorization", "Bearer $hfApiKey")
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.e("HF-STT", "Failed: ${resp.code} ${resp.message}")
                    return@use null
                }
                val text = resp.body?.string()?.trim() ?: return@use null
                if (text.startsWith("{")) {
                    return@use try {
                        val json = org.json.JSONObject(text)
                        json.optString("text").ifBlank { json.optString("generated_text") }
                    } catch (_: Exception) {
                        text
                    }
                }
                text
            }
        } catch (e: Exception) {
            android.util.Log.e("HF-STT", "error: ${e.message}", e)
            null
        }
    }

    private fun setupAIClient() {
        val apiKeys = prefsManager.getAPIKeys()
        Log.d("BaseChatActivity", "📊 Total API keys: ${apiKeys.size}, Active: ${apiKeys.count { it.isActive }}")
        Log.d("BaseChatActivity", "📊 API keys by provider: ${apiKeys.groupBy { it.provider }.mapValues { it.value.count { it.isActive } }}")
        
        if (apiKeys.isNotEmpty()) {
            aiClient = AIClient(this, apiKeys)
            val resolved = chooseBestModel(apiKeys, prefsManager.getProviderPreference())
            currentModel = resolved
            prefsManager.saveSelectedModel(currentModel)
            Log.d("BaseChatActivity", "✅ API Key یافت شد؛ حالت فعلی: ${prefsManager.getWorkingMode().name}")
        } else {
            Toast.makeText(this, "⚠️ کلید API یافت نشد", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupListeners() {
        getSendButton().setOnClickListener {
            sendMessage()
        }

        // Long-press on VoiceActionButton triggers full voice conversation mode
        try {
            val voiceView = getVoiceButton()
            if (voiceView is com.persianai.assistant.ui.VoiceActionButton) {
                voiceView.setOnLongClickListener {
                    startVoiceConversationDialog()
                    true
                }
            }
        } catch (_: Exception) {
        }

        // Default click behavior for simple voice buttons (e.g. MaterialButton).
        // If the voice view is a custom recorder view or VoiceActionButton, its own listener will handle recording/STT.
        try {
            val voiceView = getVoiceButton()
            val isVoiceActionButton = voiceView is com.persianai.assistant.ui.VoiceActionButton
            val isVoiceRecorder = voiceView is VoiceRecorderView
            if (!isVoiceActionButton && !isVoiceRecorder) {
                voiceView.setOnClickListener {
                    try {
                        if (voiceHelper.isRecording()) {
                            stopVoiceRecording()
                        } else {
                            startVoiceRecording()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("BaseChatActivity", "Voice button action failed", e)
                        Toast.makeText(this@BaseChatActivity, "خطا در ضبط صوت", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (_: Exception) {
            // Some activities may not expose a voice button
        }
        
        // تنظیم VoiceRecorderView یا VoiceActionButton
        try {
            voiceRecorderView = getVoiceButton() as? VoiceRecorderView
            if (voiceRecorderView != null) {
                voiceRecorderView!!.setListener(object : VoiceRecorderView.VoiceRecorderListener {
                    override fun onRecordingStarted() {
                        checkAudioPermissionAndStartRecording()
                    }
                    
                    override fun onRecordingCompleted(audioFile: File, durationMs: Long) {
                        transcribeAudio(audioFile)
                    }
                    
                    override fun onRecordingCancelled() {
                        Toast.makeText(this@BaseChatActivity, "❌ ضبط لغو شد", Toast.LENGTH_SHORT).show()
                    }
                })
                android.util.Log.d("BaseChatActivity", "VoiceRecorderView initialized successfully")
            } else {
                android.util.Log.w("BaseChatActivity", "VoiceRecorderView not found, voice recording disabled")
            }
        } catch (e: Exception) {
            android.util.Log.e("BaseChatActivity", "Error initializing VoiceRecorderView", e)
        }

        // If a unified VoiceActionButton exists in the layout, wire it up so
        // chat activities automatically benefit from the unified recorder.
        try {
            val listenerImpl = object : com.persianai.assistant.ui.VoiceActionButton.Listener {
                    override fun onRecordingStarted() {
                        onVoiceRecordingStarted()
                    }

                    override fun onRecordingCompleted(audioFile: File, durationMs: Long) {
                        // VoiceActionButton خودش STT را انجام می‌دهد؛ فقط UI را ریست می‌کنیم
                        onVoiceRecordingCompleted(audioFile, durationMs)
                    }

                    override fun onTranscript(text: String) {
                        try {
                            handleTranscript(text)
                        } catch (_: Exception) { }
                    }

                    override fun onRecordingError(error: String) {
                        onVoiceRecordingError(error)
                    }
                }

            // 1) Try explicit ids used across layouts
            val vab1 = findViewById<com.persianai.assistant.ui.VoiceActionButton?>(R.id.voiceButton)
            if (vab1 != null) {
                vab1.setListener(listenerImpl)
                android.util.Log.d("BaseChatActivity", "VoiceActionButton wired (id=voiceButton)")
                return
            }

            // 2) Try alternative id used in some layouts
            val vab2 = findViewById<com.persianai.assistant.ui.VoiceActionButton?>(R.id.voiceActionButton)
            if (vab2 != null) {
                vab2.setListener(listenerImpl)
                android.util.Log.d("BaseChatActivity", "VoiceActionButton wired (id=voiceActionButton)")
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("BaseChatActivity", "VoiceActionButton wiring skipped: ${e.message}")
        }
    }

    private fun startVoiceConversationDialog() {
        try {
            if (voiceConversationDialog?.isShowing == true) return
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                Toast.makeText(this, "⚠️ برای مکالمه صوتی مجوز میکروفن لازم است", Toast.LENGTH_SHORT).show()
                return
            }

            val dialogView = layoutInflater.inflate(com.persianai.assistant.R.layout.dialog_voice_conversation, null, false)
            val statusText = dialogView.findViewById<TextView>(com.persianai.assistant.R.id.statusText)
            val lastText = dialogView.findViewById<TextView>(com.persianai.assistant.R.id.lastText)
            val stopButton = dialogView.findViewById<Button>(com.persianai.assistant.R.id.stopButton)

            voiceConversationDialog = MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create()

            stopButton.setOnClickListener {
                stopVoiceConversationDialog()
            }

            voiceConversationDialog?.show()

            // Initial assistant prompt
            val intro = (getIntroMessage()?.takeIf { it.isNotBlank() } ?: "سلام! چطور می‌تونم کمکت کنم؟")
            lastText.text = intro
            ttsHelper.speak(intro)

            voiceConversationJob?.cancel()
            voiceConversationJob = lifecycleScope.launch {
                val engine = UnifiedVoiceEngine(this@BaseChatActivity)
                while (voiceConversationDialog?.isShowing == true) {
                    try {
                        statusText.text = "🎤 گوش می‌دم..."
                        val recording = recordWithVad(engine)
                        if (recording == null) {
                            statusText.text = "⚠️ چیزی شنیده نشد"
                            kotlinx.coroutines.delay(600)
                            continue
                        }

                        statusText.text = "📝 تبدیل گفتار به متن..."
                        // Use OnlineSTTService with Liara priority and GapGPT fallback
                        val sttResult = onlineSTT.transcribeAudio(recording.file)
                        val userText = if (sttResult.isSuccess) sttResult.text else ""
                        if (userText.isBlank()) {
                            statusText.text = "⚠️ متن تشخیص داده نشد"
                            kotlinx.coroutines.delay(600)
                            continue
                        }

                        lastText.text = "شما: $userText"
                        addMessage(ChatMessage(role = MessageRole.USER, content = userText))

                        statusText.text = "🤖 در حال پاسخ..."
                        // Route through QueryRouter تا آنلاین (OpenRouter→Liara) یا آفلاین اجرا شود
                        val router = com.persianai.assistant.core.QueryRouter(this@BaseChatActivity)
                        val result = router.routeQuery(userText)
                        val reply = result.response
                        addMessage(ChatMessage(role = MessageRole.ASSISTANT, content = reply, isError = !result.success))
                        lastText.text = "دستیار: ${reply.take(300)}"

                        // TTS playback with GapGPT -> Haaniye priority
                        statusText.text = "🔊 در حال پخش پاسخ..."
                        try {
                            ttsHelper.speakOnlineFirst(reply)
                        } catch (e: Exception) {
                            android.util.Log.w("BaseChatActivity", "TTS playback failed: ${e.message}")
                        }

                        statusText.text = "🎤 دوباره گوش می‌دم..."
                        kotlinx.coroutines.delay(500)
                    } catch (e: Exception) {
                        android.util.Log.e("BaseChatActivity", "Voice conversation loop error", e)
                        statusText.text = "❌ خطا: ${e.message}"
                        kotlinx.coroutines.delay(800)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BaseChatActivity", "Failed to start voice conversation dialog", e)
            Toast.makeText(this, "❌ خطا در مکالمه صوتی", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVoiceConversationDialog() {
        try {
            voiceConversationJob?.cancel()
            voiceConversationJob = null
            voiceConversationDialog?.dismiss()
            voiceConversationDialog = null
        } catch (_: Exception) {
        }
    }

    private suspend fun recordWithVad(engine: UnifiedVoiceEngine): com.persianai.assistant.services.RecordingResult? {
        return try {
            if (!engine.hasRequiredPermissions()) return null

            val start = engine.startRecording()
            if (start.isFailure) return null

            val startTime = System.currentTimeMillis()
            var hasSpeech = false
            var lastSpeechTime = 0L
            val maxTotalMs = 10_000L
            val maxWaitForSpeechMs = 4_000L
            val silenceStopMs = 1_200L
            val threshold = 900

            while (engine.isRecordingInProgress()) {
                val now = System.currentTimeMillis()
                val amp = engine.getCurrentAmplitude()
                if (amp > threshold) {
                    hasSpeech = true
                    lastSpeechTime = now
                }

                val total = now - startTime
                if (!hasSpeech && total > maxWaitForSpeechMs) break
                if (hasSpeech && (now - lastSpeechTime) > silenceStopMs) break
                if (total > maxTotalMs) break

                kotlinx.coroutines.delay(120)
            }

            val stop = engine.stopRecording()
            stop.getOrNull()
        } catch (e: Exception) {
            try { engine.cancelRecording() } catch (_: Exception) {}
            null
        }
    }

    protected fun sendMessage() {
        val text = getMessageInput().text.toString().trim()
        if (text.isEmpty()) return

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            content = text,
            timestamp = System.currentTimeMillis()
        )
        addMessage(userMessage)
        getMessageInput().text?.clear()

        getSendButton().isEnabled = false

        lifecycleScope.launch {
            try {
                Log.d("BaseChatActivity", "💬 Processing message via handleRequest")
                
                // استفاده از handleRequest که در Activity های مختلف override می‌شود
                val responseContent = handleRequest(text)

                if (!responseContent.isNullOrBlank()) {
                    val responseMessage = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = responseContent,
                        timestamp = System.currentTimeMillis()
                    )
                    addMessage(responseMessage)
                } else {
                    val errorMessage = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "❌ خطا: نتوانستم پاسخ دریافت کنم",
                        timestamp = System.currentTimeMillis(),
                        isError = true
                    )
                    addMessage(errorMessage)
                }
            } catch (e: Exception) {
                Log.e("BaseChatActivity", "❌ Error in sendMessage: ${e.message}", e)
                val errorMessage = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "❌ خطا: ${e.message}",
                    timestamp = System.currentTimeMillis(),
                    isError = true
                )
                addMessage(errorMessage)
            } finally {
                getSendButton().isEnabled = true
            }
        }
    }

    protected open suspend fun handleRequest(text: String): String = withContext(Dispatchers.IO) {
        val workingMode = prefsManager.getWorkingMode()
        val apiKeys = prefsManager.getAPIKeys()
        val hasValidKeys = apiKeys.isNotEmpty() && apiKeys.any { it.isActive }
        val canUseOnline = (workingMode == PreferencesManager.WorkingMode.ONLINE || workingMode == PreferencesManager.WorkingMode.HYBRID) && hasValidKeys && aiClient != null

        fun isLikelyComplex(t: String): Boolean {
            val s = t.trim()
            val lower = s.lowercase()
            if (s.length >= 120) return true
            return lower.contains("تحلیل") ||
                lower.contains("مقاله") ||
                lower.contains("خلاصه") ||
                lower.contains("برنامه") ||
                lower.contains("استراتژی") ||
                lower.contains("روان") ||
                lower.contains("افسرد") ||
                lower.contains("اضطراب") ||
                lower.contains("مسیر شغلی") ||
                lower.contains("رزومه") ||
                lower.contains("پیشنهاد کتاب") ||
                lower.contains("پیشنهاد فیلم") ||
                lower.contains("research") ||
                lower.contains("plan")
        }

        fun shouldUseOnlineFirst(): Boolean {
            // Policy:
            // - OFFLINE: never online
            // - HYBRID / ONLINE: try online first when possible; fallback to offline
            if (workingMode == PreferencesManager.WorkingMode.OFFLINE) return false
            if (!canUseOnline) return false
            // HYBRID/ONLINE: try online first, fallback to offline if online fails
            return true
        }

        suspend fun tryOnline(): String? {
            if (!canUseOnline) return null
            var model = chooseBestModel(apiKeys, prefsManager.getProviderPreference())
            var attempts = 0
            val maxAttempts = 2 // حداکثر دو تلاش برای GAPGPT
            
            while (attempts < maxAttempts) {
                try {
                    currentModel = model
                    android.util.Log.d("BaseChatActivity", "📡 tryOnline attempt ${attempts + 1} model=${model.name}")
                    val response = aiClient!!.sendMessage(
                        model,
                        messages,
                        buildSystemPromptForOnlineRequest()
                    )
                    return response.content
                } catch (e: Exception) {
                    android.util.Log.w("BaseChatActivity", "⚠️ tryOnline failed with ${model.name}: ${e.message}")
                    
                    // اگر مدل GAPGPT بود و خطا داد، به مدل بعدی فالینک کن
                    if (model.provider == com.persianai.assistant.models.AIProvider.GAPGPT && attempts == 0) {
                        model = getNextGAPGPTModel(model)
                        android.util.Log.d("BaseChatActivity", "🔄 Retrying with fallback model: ${model.name}")
                    } else {
                        // برای Liara یا تلاش دوم GAPGPT، دیگر تلاش نکن
                        break
                    }
                    attempts++
                }
            }
            return null
        }

        val onlineFirst = shouldUseOnlineFirst()
        if (onlineFirst) {
            val online = tryOnline()
            if (!online.isNullOrBlank()) return@withContext online

            val offline = offlineRespond(text)
            if (!offline.isNullOrBlank()) return@withContext offline
            return@withContext "❌ خطا در پردازش درخواست"
        }

        // OFFLINE: strictly offline
        if (workingMode != PreferencesManager.WorkingMode.OFFLINE && !canUseOnline) {
            val offline = offlineRespond(text)
            if (!offline.isNullOrBlank()) return@withContext offline
            return@withContext "⚠️ پاسخ آنلاین فعال نیست. لطفاً در تنظیمات کلید API معتبر وارد کنید یا یک کلید فعال را انتخاب کنید."
        }

        val offline = offlineRespond(text)
        if (!offline.isNullOrBlank()) return@withContext offline
        return@withContext "❌ خطا در پردازش درخواست"
    }

    private fun offlineRespond(text: String): String? {
        // فال‌بک آفلاین حذف شده - فقط خطا برمی‌گردانیم
        return null
    }

    protected open fun offlineDomainRespond(text: String): String? = null

    /**
     * یافتن مسیر مدل tinyllama دانلود‌شده (دستی یا از طریق OfflineModelManager)
     */
    private fun findOfflineModelPath(): String? {
        // Offline models disabled - always return null
        return null
        /*
        return try {
            // اولویت با مدل انتخاب‌شده کاربر؛ در غیر اینصورت هر مدل دانلودشده
            val preferred = prefsManager.getOfflineModelType()
            val info = modelDownloadManager.findDownloadedModel(preferred) ?: modelDownloadManager.findDownloadedModel()
            if (info != null) {
                val file = modelDownloadManager.getModelFile(info)
                if (file.exists()) file.absolutePath else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        */
    }

    protected open fun shouldUseOnlinePriority(): Boolean = false

    protected open fun getIntroMessage(): String? = null

    private fun maybeShowIntroMessage() {
        if (!conversationLoaded) return
        if (!this::chatAdapter.isInitialized) return
        if (messages.isNotEmpty()) return
        val intro = getIntroMessage()?.trim().orEmpty()
        if (intro.isBlank()) return
        addMessage(ChatMessage(role = MessageRole.ASSISTANT, content = intro))
    }

    /**
     * جستجوی سبک با DuckDuckGo Instant Answer API (بدون نیاز به کلید)
     */
    private fun tryDuckDuckGo(query: String): String? {
        return try {
            if (!isNetworkAvailable()) return null
            val url = "https://api.duckduckgo.com/?q=" +
                java.net.URLEncoder.encode(query, "UTF-8") +
                "&format=json&no_redirect=1&no_html=1"
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val obj = JSONObject(body)
                val abstractText = obj.optString("AbstractText").takeIf { it.isNotBlank() }
                val heading = obj.optString("Heading").takeIf { it.isNotBlank() }
                val firstRelated = obj.optJSONArray("RelatedTopics")?.let { arr ->
                    if (arr.length() > 0) {
                        arr.optJSONObject(0)?.optString("Text")?.takeIf { it.isNotBlank() }
                    } else null
                }
                val candidate = abstractText ?: firstRelated
                return if (!candidate.isNullOrBlank()) {
                    val title = heading ?: "نتیجه جستجو"
                    "🔎 $title:\n$candidate"
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    protected open fun getSystemPrompt(): String {
        return "شما یک دستیار هوشمند فارسی هستید."
    }

    protected open fun getModuleIdForPrompt(): String {
        return this::class.java.simpleName
    }

    protected fun refreshAPIKeys() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@BaseChatActivity, "در حال به‌روزرسانی کلیدها...", Toast.LENGTH_SHORT).show()
                val result = AutoProvisioningManager.autoProvision(this@BaseChatActivity)
                result.onSuccess {
                    Toast.makeText(this@BaseChatActivity, "✅ ${it.count { k -> k.isActive }} کلید فعال شد", Toast.LENGTH_SHORT).show()
                    setupAIClient()
                }.onFailure { e ->
                    Toast.makeText(this@BaseChatActivity, "❌ خطا: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@BaseChatActivity, "❌ خطا: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildSystemPromptForOnlineRequest(): String {
        val moduleId = try {
            getModuleIdForPrompt()
        } catch (_: Exception) {
            this::class.java.simpleName
        }

        val base = getSystemPrompt().trim()

        val namespace = try {
            intent?.getStringExtra("namespace")
        } catch (_: Exception) {
            null
        }

        val hiddenContext = buildString {
            appendLine("[APP_CONTEXT]")
            appendLine("app=PersianAIAssistant")
            appendLine("module=$moduleId")
            if (!namespace.isNullOrBlank()) appendLine("namespace=$namespace")
            appendLine("rules=فارسی؛ کوتاه و مفید؛ اگر اطلاعات کافی نیست سوال بپرس؛ در پاسخ از متن‌های ثابت تکراری پرهیز کن.")
            appendLine("[/APP_CONTEXT]")
        }.trim()

        return buildString {
            if (base.isNotBlank()) {
                appendLine(base)
                appendLine()
            }
            append(hiddenContext)
        }
    }

    protected fun addMessage(message: ChatMessage) {
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        getRecyclerView().smoothScrollToPosition(messages.size - 1)
        if (message.role == MessageRole.ASSISTANT && !message.isError) {
            ttsHelper.speak(message.content)
        }
        // Persist message into current conversation
        try {
            currentConversation.messages.add(message)
            // نام‌گذاری خودکار پس از اولین پیام کاربر
            if (message.role == MessageRole.USER && currentConversation.title == "چت جدید") {
                currentConversation.title = currentConversation.generateTitle()
            }
            lifecycleScope.launch {
                try {
                    conversationStorage.saveConversation(currentConversation)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    private fun checkAudioPermissionAndStartRecording() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_RECORD_AUDIO)
        }
    }

    protected fun transcribeAudio(audioFile: File) {
        lifecycleScope.launch {
            try {
                val text = try {
                    // Use OnlineSTTService with Liara priority and GapGPT fallback
                    val sttResult = onlineSTT.transcribeAudio(audioFile)
                    if (sttResult.isSuccess) sttResult.text else ""
                } catch (e: Exception) {
                    Log.e("BaseChatActivity", "STT failed: ${e.message}")
                    ""
                }

                if (text.isNotBlank()) {
                    applyTranscriptToInput(text)
                    Toast.makeText(this@BaseChatActivity, "✅ صوت به متن تبدیل شد؛ می‌توانید ویرایش و سپس ارسال کنید", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this@BaseChatActivity,
                        "⚠️ تبدیل گفتار به متن موفق نبود.\n\n" +
                            "اگر آنلاین فعال باشد اول لیارا امتحان می‌شود؛ در غیر اینصورت Haaniye آفلاین. واضح‌تر صحبت کنید.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("BaseChatActivity", "Transcription failed: ${e.message}", e)
                Toast.makeText(
                    this@BaseChatActivity,
                    "❌ خطا: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "⚠️ مجوز ضبط صوت لازم است", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsHelper.shutdown()
        speechRecognizer.destroy()
        voiceHelper.cancelRecording()
    }
    
    // ===== Voice Recording Setup =====
    
    protected open fun setupVoiceRecording() {
        voiceHelper.setListener(object : VoiceRecordingHelper.RecordingListener {
            override fun onRecordingStarted() {
                Log.d("BaseChatActivity", "Recording started")
                onVoiceRecordingStarted()
            }
            
            override fun onRecordingCompleted(audioFile: File, durationMs: Long) {
                Log.d("BaseChatActivity", "Recording completed: ${audioFile.absolutePath}, Duration: ${durationMs}ms")
                onVoiceRecordingCompleted(audioFile, durationMs)
            }
            
            override fun onRecordingCancelled() {
                Log.d("BaseChatActivity", "Recording cancelled")
                onVoiceRecordingCancelled()
            }
            
            override fun onRecordingError(error: String) {
                Log.e("BaseChatActivity", "Recording error: $error")
                onVoiceRecordingError(error)
            }
        })
    }
    
    protected open fun onVoiceRecordingStarted() {
        Log.d("BaseChatActivity", "Voice recording started")
        try { getVoiceButton().alpha = 0.5f } catch (_: Exception) {}
    }
    
    protected open fun onVoiceRecordingCompleted(audioFile: File, durationMs: Long) {
        Log.d("BaseChatActivity", "Voice recording completed")
        try { getVoiceButton().alpha = 1.0f } catch (_: Exception) {}
        // پس از اتمام ضبط: تبدیل گفتار به متن و ارسال به چت
        transcribeAudio(audioFile)
    }
    
    protected open fun onVoiceRecordingCancelled() {
        Log.d("BaseChatActivity", "Voice recording cancelled")
        try { getVoiceButton().alpha = 1.0f } catch (_: Exception) {}
    }
    
    protected open fun onVoiceRecordingError(error: String) {
        Log.e("BaseChatActivity", "Voice recording error: $error")
        try { getVoiceButton().alpha = 1.0f } catch (_: Exception) {}
        Toast.makeText(this, "خطا: $error", Toast.LENGTH_SHORT).show()
    }
    
    protected fun startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            voiceHelper.startRecording()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }
    }
    
    protected fun stopVoiceRecording() {
        voiceHelper.stopRecording()
    }
    
    protected fun cancelVoiceRecording() {
        voiceHelper.cancelRecording()
    }
    
    private fun showChatDisabledMessage() {
        MaterialAlertDialogBuilder(this)
            .setTitle("🚫 قابلیت چت موقتاً غیرفعال است")
            .setMessage("در حال حاضر بخش مکالمه هوش مصنوعی برای بهبود و توسعه غیرفعال شده است. لطفاً از سایر قابلیت‌های برنامه استفاده کنید.")
            .setPositiveButton("فهمیدم") { _, _ ->
                // Navigate to dashboard
                startActivity(Intent(this, DashboardActivity::class.java))
                finish()
            }
            .setCancelable(false)
            .show()
    }
}
