package com.aesthetic.backend.dto.response

import java.time.LocalTime

data class TimeSlotResponse(
    val startTime: LocalTime,
    val endTime: LocalTime,
    val isAvailable: Boolean
)
