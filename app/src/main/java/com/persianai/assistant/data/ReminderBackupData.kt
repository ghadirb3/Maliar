package com.persianai.assistant.data

data class ReminderBackupData(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val type: String = "SIMPLE",
    val priority: String = "MEDIUM",
    val alertType: String = "NOTIFICATION",
    val triggerTime: Long = 0,
    val repeatPattern: String = "ONCE",
    val customRepeatDays: List<Int> = emptyList(),
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class SmartRemindersBackup(
    val reminders: List<ReminderBackupData> = emptyList()
)