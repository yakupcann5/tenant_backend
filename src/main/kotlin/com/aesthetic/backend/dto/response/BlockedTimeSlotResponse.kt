package com.aesthetic.backend.dto.response

import java.time.LocalDate
import java.time.LocalTime

data class BlockedTimeSlotResponse(
    val id: String,
    val staffId: String?,
    val staffName: String?,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val reason: String?
)
