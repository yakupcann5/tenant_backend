package com.aesthetic.backend.dto.response

data class PlanUsageResponse(
    val staffCount: Long,
    val maxStaff: Int,
    val appointmentCount: Long,
    val maxAppointments: Int,
    val storageMB: Long,
    val maxStorageMB: Int
)
