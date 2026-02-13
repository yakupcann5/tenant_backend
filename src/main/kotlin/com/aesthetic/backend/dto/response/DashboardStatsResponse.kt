package com.aesthetic.backend.dto.response

import java.math.BigDecimal
import java.time.LocalDate

data class DashboardStatsResponse(
    val date: LocalDate,
    val totalAppointments: Long,
    val completed: Long,
    val pending: Long,
    val confirmed: Long,
    val cancelled: Long,
    val noShow: Long,
    val revenue: BigDecimal
)
