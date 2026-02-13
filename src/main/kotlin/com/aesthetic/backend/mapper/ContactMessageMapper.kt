package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.contact.ContactMessage
import com.aesthetic.backend.dto.response.ContactMessageResponse

fun ContactMessage.toResponse(): ContactMessageResponse = ContactMessageResponse(
    id = id!!,
    name = name,
    email = email,
    phone = phone,
    subject = subject,
    message = message,
    isRead = isRead,
    readAt = readAt,
    createdAt = createdAt
)
