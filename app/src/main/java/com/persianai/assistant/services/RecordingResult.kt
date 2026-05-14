package com.persianai.assistant.services

import java.io.File

/**
 * نتیجه ضبط صدا
 * @param file فایل ضبط شده
 * @param duration مدت زمان ضبط به میلی‌ثانیه
 * @param startTime زمان شروع ضبط (timestamp)
 */
data class RecordingResult(
    val file: File,
    val duration: Long,
    val startTime: Long
)
