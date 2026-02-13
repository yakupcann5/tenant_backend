package com.aesthetic.backend.dto.response

data class UserDataExportResponse(
    val user: UserResponse,
    val appointments: List<AppointmentSummaryResponse>,
    val consents: List<ConsentRecordResponse>
)
