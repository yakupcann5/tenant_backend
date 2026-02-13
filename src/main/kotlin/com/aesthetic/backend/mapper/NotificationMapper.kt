package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.notification.Notification
import com.aesthetic.backend.domain.notification.NotificationTemplate
import com.aesthetic.backend.dto.response.NotificationResponse
import com.aesthetic.backend.dto.response.NotificationTemplateResponse

fun Notification.toResponse(): NotificationResponse = NotificationResponse(
    id = id!!,
    type = type,
    recipientEmail = recipientEmail,
    subject = subject,
    deliveryStatus = deliveryStatus,
    sentAt = sentAt,
    errorMessage = errorMessage,
    createdAt = createdAt
)

fun NotificationTemplate.toResponse(): NotificationTemplateResponse = NotificationTemplateResponse(
    id = id!!,
    type = type,
    subject = subject,
    body = body,
    isActive = isActive
)
