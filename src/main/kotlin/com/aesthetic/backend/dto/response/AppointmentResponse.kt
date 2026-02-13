package com.aesthetic.backend.dto.response

import com.aesthetic.backend.domain.appointment.AppointmentStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class AppointmentResponse(
    val id: String,
    val clientName: String,
    val clientEmail: String,
    val clientPhone: String,
    val services: List<AppointmentServiceItemResponse>,
    val staffId: String?,
    val staffName: String?,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val totalDurationMinutes: Int,
    val totalPrice: BigDecimal,
    val status: AppointmentStatus,
    val notes: String?,
    val recurringGroupId: String?,
    val recurrenceRule: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?
)
