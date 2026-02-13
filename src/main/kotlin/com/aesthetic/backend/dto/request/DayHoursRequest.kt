package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotNull
import java.time.DayOfWeek
import java.time.LocalTime

data class DayHoursRequest(
    @field:NotNull(message = "Gün bilgisi zorunludur")
    val dayOfWeek: DayOfWeek,

    val isOpen: Boolean = true,

    val startTime: LocalTime = LocalTime.of(9, 0),

    val endTime: LocalTime = LocalTime.of(18, 0),

    val breakStartTime: LocalTime? = null,

    val breakEndTime: LocalTime? = null
)
