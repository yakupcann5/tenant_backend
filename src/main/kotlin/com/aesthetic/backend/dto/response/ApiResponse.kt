package com.aesthetic.backend.dto.response

import java.time.Instant

data class ApiResponse<T>(
    val success: Boolean = true,
    val data: T? = null,
    val message: String? = null,
    val timestamp: Instant = Instant.now()
)
