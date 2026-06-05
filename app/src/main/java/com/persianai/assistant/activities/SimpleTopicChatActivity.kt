package com.persianai.assistant.activities

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.persianai.assistant.databinding.ActivityChatBinding

abstract class SimpleTopicChatActivity : BaseChatActivity() {

    protected lateinit var chatBinding: ActivityChatBinding

    abstract fun getToolbarTitle(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        chatBinding = ActivityChatBinding.inflate(layoutInflater)
        binding = chatBinding
        setContentView(chatBinding.root)
        setSupportActionBar(chatBinding.toolbar)

        supportActionBar?.apply {
            title = getToolbarTitle()
            setDisplayHomeAsUpEnabled(true)
        }

        setupChatUI()
    }

    override fun shouldUseOnlinePriority(): Boolean = true

    override fun getRecyclerView(): RecyclerView = chatBinding.messagesRecyclerView

    override fun getMessageInput(): TextInputEditText = chatBinding.messageInput

    override fun getSendButton(): View = chatBinding.sendButton

    override fun getVoiceButton(): View = chatBinding.voiceButton

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
