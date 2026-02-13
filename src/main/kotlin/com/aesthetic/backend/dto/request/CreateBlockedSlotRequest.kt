package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotNull
import java.time.LocalDate
import java.time.LocalTime

data class CreateBlockedSlotRequest(
    @field:NotNull(message = "Tarih zorunludur")
    val date: LocalDate,

    @field:NotNull(message = "Başlangıç saati zorunludur")
    val startTime: LocalTime,

    @field:NotNull(message = "Bitiş saati zorunludur")
    val endTime: LocalTime,

    val staffId: String? = null,

    val reason: String? = null
)
