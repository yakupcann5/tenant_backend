package com.aesthetic.backend.dto.response

import com.aesthetic.backend.domain.notification.NotificationType

data class NotificationTemplateResponse(
    val id: String,
    val type: NotificationType,
    val subject: String,
    val body: String,
    val isActive: Boolean
)
