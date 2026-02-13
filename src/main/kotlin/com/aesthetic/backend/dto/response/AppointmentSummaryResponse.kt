package com.aesthetic.backend.dto.response

import com.aesthetic.backend.domain.appointment.AppointmentStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

data class AppointmentSummaryResponse(
    val id: String,
    val clientName: String,
    val primaryServiceName: String?,
    val staffName: String?,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val totalPrice: BigDecimal,
    val status: AppointmentStatus
)
