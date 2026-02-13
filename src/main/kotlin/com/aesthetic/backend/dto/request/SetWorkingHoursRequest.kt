package com.aesthetic.backend.dto.request

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty

data class SetWorkingHoursRequest(
    @field:NotEmpty(message = "Çalışma saatleri listesi boş olamaz")
    @field:Valid
    val hours: List<DayHoursRequest>
)
