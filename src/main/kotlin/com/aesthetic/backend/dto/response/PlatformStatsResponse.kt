package com.aesthetic.backend.dto.response

data class PlatformStatsResponse(
    val totalTenants: Long,
    val activeTenants: Long,
    val totalUsers: Long,
    val totalAppointments: Long,
    val trialTenants: Long,
    val activePlanTenants: Long
)
