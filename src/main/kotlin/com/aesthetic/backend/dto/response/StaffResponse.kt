package com.aesthetic.backend.dto.response

import java.time.Instant

data class StaffResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val title: String?,
    val image: String?,
    val isActive: Boolean,
    val createdAt: Instant?
)
