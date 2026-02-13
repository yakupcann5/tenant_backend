package com.aesthetic.backend.domain.notification

import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(
    name = "notification_templates",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_notification_templates_tenant_type", columnNames = ["tenant_id", "type"])
    ]
)
class NotificationTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: NotificationType,

    var subject: String = "",

    @Column(columnDefinition = "TEXT", nullable = false)
    var body: String,

    @Column(name = "is_active")
    var isActive: Boolean = true,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
