package com.persianai.assistant.services

import java.io.File

/**
 * نتیجه ضبط صدا.
 */
data class RecordingResult(
    val file: File,
    val duration: Long,
    val startTime: Long
)
