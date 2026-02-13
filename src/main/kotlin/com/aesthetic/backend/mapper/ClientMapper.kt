package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.response.ClientListResponse

fun User.toClientListResponse(appointmentCount: Long = 0): ClientListResponse {
    return ClientListResponse(
        id = id!!,
        firstName = firstName,
        lastName = lastName,
        email = email,
        phone = phone,
        isBlacklisted = isBlacklisted,
        appointmentCount = appointmentCount,
        createdAt = createdAt
    )
}
