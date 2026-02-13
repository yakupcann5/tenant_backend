package com.aesthetic.backend.dto.response

import java.time.Instant

data class ErrorResponse(
    val success: Boolean = false,
    val error: String,
    val code: ErrorCode,
    val details: Map<String, String>? = null,
    val timestamp: Instant = Instant.now()
)
