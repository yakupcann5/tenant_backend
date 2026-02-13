package com.aesthetic.backend.dto.response

import java.time.Instant

data class ContactMessageResponse(
    val id: String,
    val name: String,
    val email: String,
    val phone: String,
    val subject: String,
    val message: String,
    val isRead: Boolean,
    val readAt: Instant?,
    val createdAt: Instant?
)
