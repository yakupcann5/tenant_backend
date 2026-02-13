package com.aesthetic.backend.dto.notification

import com.aesthetic.backend.domain.notification.NotificationType

data class NotificationContext(
    val tenantId: String,
    val recipientEmail: String,
    val recipientPhone: String = "",
    val recipientName: String = "",
    val recipientId: String? = null,
    val variables: Map<String, String> = emptyMap(),
    val notificationType: NotificationType
)
