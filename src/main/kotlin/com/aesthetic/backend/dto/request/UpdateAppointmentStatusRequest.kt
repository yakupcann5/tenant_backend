package com.aesthetic.backend.dto.request

import com.aesthetic.backend.domain.appointment.AppointmentStatus
import jakarta.validation.constraints.NotNull

data class UpdateAppointmentStatusRequest(
    @field:NotNull(message = "Durum bilgisi zorunludur")
    val status: AppointmentStatus,

    val reason: String? = null
)
