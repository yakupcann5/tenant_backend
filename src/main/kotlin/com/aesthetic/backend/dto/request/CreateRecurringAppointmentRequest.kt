package com.aesthetic.backend.dto.request

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class CreateRecurringAppointmentRequest(
    @field:NotNull(message = "Temel randevu bilgisi zorunludur")
    @field:Valid
    val baseAppointment: CreateAppointmentRequest,

    @field:NotBlank(message = "Tekrar kuralı zorunludur")
    val recurrenceRule: String,

    @field:Min(value = 2, message = "En az 2 tekrar olmalıdır")
    @field:Max(value = 52, message = "En fazla 52 tekrar olabilir")
    val count: Int
)
