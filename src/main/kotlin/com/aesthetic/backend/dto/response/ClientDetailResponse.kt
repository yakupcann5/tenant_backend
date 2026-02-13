package com.aesthetic.backend.dto.response

import java.time.Instant
import java.time.LocalDate

data class ClientDetailResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val image: String?,
    val isBlacklisted: Boolean,
    val blacklistReason: String?,
    val noShowCount: Int,
    val appointmentCount: Long,
    val lastAppointmentDate: LocalDate?,
    val totalSpent: java.math.BigDecimal,
    val createdAt: Instant?
)
