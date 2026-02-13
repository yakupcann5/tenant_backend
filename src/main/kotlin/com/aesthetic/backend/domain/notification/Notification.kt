package com.aesthetic.backend.domain.notification

import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

@Entity
@Table(name = "notifications")
class Notification(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: NotificationType,

    @Column(name = "recipient_id")
    var recipientId: String? = null,

    @Column(name = "recipient_email")
    var recipientEmail: String = "",

    @Column(name = "recipient_phone")
    var recipientPhone: String = "",

    var subject: String = "",

    @Column(columnDefinition = "TEXT", nullable = false)
    var body: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false)
    var deliveryStatus: DeliveryStatus = DeliveryStatus.PENDING,

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    @Column(name = "retry_count")
    var retryCount: Int = 0,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null
) : TenantAwareEntity()
