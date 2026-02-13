package com.aesthetic.backend.dto.response

import java.time.Instant

data class ClientListResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val isBlacklisted: Boolean,
    val appointmentCount: Long,
    val createdAt: Instant?
)
