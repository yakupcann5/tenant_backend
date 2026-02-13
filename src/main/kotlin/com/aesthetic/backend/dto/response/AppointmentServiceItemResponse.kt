package com.aesthetic.backend.dto.response

import java.math.BigDecimal

data class AppointmentServiceItemResponse(
    val serviceId: String,
    val serviceName: String,
    val price: BigDecimal,
    val durationMinutes: Int
)
