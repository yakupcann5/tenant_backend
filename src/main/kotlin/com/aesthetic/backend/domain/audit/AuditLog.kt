package com.aesthetic.backend.domain.audit

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

@Entity
@Table(name = "audit_logs")
class AuditLog(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "tenant_id", nullable = false)
    var tenantId: String = "",

    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    @Column(nullable = false, length = 100)
    var action: String = "",

    @Column(name = "entity_type", length = 100)
    var entityType: String = "",

    @Column(name = "entity_id")
    var entityId: String = "",

    @Column(columnDefinition = "JSON")
    var details: String? = null,

    @Column(name = "ip_address", length = 45)
    var ipAddress: String? = null,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null
)
