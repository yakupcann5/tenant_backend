package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.response.PublicStaffResponse
import com.aesthetic.backend.dto.response.StaffResponse

fun User.toStaffResponse(): StaffResponse {
    return StaffResponse(
        id = id!!,
        firstName = firstName,
        lastName = lastName,
        email = email,
        phone = phone,
        title = title,
        image = image,
        isActive = isActive,
        createdAt = createdAt
    )
}

fun User.toPublicStaffResponse(): PublicStaffResponse {
    return PublicStaffResponse(
        firstName = firstName,
        lastName = lastName,
        title = title,
        image = image
    )
}
