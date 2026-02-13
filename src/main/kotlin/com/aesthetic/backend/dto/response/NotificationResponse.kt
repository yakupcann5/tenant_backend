package com.aesthetic.backend.dto.response

import com.aesthetic.backend.domain.notification.DeliveryStatus
import com.aesthetic.backend.domain.notification.NotificationType
import java.time.Instant

data class NotificationResponse(
    val id: String,
    val type: NotificationType,
    val recipientEmail: String,
    val subject: String,
    val deliveryStatus: DeliveryStatus,
    val sentAt: Instant?,
    val errorMessage: String?,
    val createdAt: Instant?
)
