package com.coderred.andclaw.data

data class SessionLogEntry(
    val timestamp: String,
    val role: String,
    val model: String?,
    val contentPreview: String?,
    val errorMessage: String?,
    val stopReason: String?,
    val tokenUsage: Int,
)
