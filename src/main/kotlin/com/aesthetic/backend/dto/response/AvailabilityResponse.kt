package com.aesthetic.backend.dto.response

import java.time.LocalDate

data class AvailabilityResponse(
    val date: LocalDate,
    val staffId: String?,
    val staffName: String?,
    val slots: List<TimeSlotResponse>
)
