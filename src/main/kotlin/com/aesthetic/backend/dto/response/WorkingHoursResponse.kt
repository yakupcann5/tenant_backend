package com.aesthetic.backend.dto.response

import java.time.DayOfWeek
import java.time.LocalTime

data class WorkingHoursResponse(
    val id: String,
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val breakStartTime: LocalTime?,
    val breakEndTime: LocalTime?,
    val isOpen: Boolean
)
