package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotNull
import java.time.LocalDate
import java.time.LocalTime

data class RescheduleAppointmentRequest(
    @field:NotNull(message = "Tarih zorunludur")
    val date: LocalDate,

    @field:NotNull(message = "Başlangıç saati zorunludur")
    val startTime: LocalTime,

    val staffId: String? = null
)
