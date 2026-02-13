package com.aesthetic.backend.dto.response

import com.aesthetic.backend.domain.user.Role
import java.time.Instant

data class UserResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val role: Role,
    val phone: String,
    val image: String?,
    val createdAt: Instant?
)
