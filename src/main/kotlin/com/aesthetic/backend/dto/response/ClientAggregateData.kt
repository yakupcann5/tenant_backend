package com.aesthetic.backend.dto.response

import java.math.BigDecimal
import java.time.LocalDate

data class ClientAggregateData(
    val appointmentCount: Long,
    val lastAppointmentDate: LocalDate?,
    val totalSpent: BigDecimal
)
