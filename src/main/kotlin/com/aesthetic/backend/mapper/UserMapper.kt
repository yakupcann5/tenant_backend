package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.response.UserResponse

fun User.toResponse(): UserResponse = UserResponse(
    id = id!!,
    firstName = firstName,
    lastName = lastName,
    email = email,
    role = role,
    phone = phone,
    image = image,
    createdAt = createdAt
)
