package com.aesthetic.backend.dto.response

import java.time.LocalDate

data class RecurringAppointmentResponse(
    val groupId: String,
    val created: List<AppointmentSummaryResponse>,
    val skippedDates: List<LocalDate>,
    val totalRequested: Int,
    val totalCreated: Int
)
