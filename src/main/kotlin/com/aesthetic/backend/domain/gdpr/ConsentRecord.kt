package com.aesthetic.backend.domain.gdpr

import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(name = "consent_records")
class ConsentRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false)
    var consentType: ConsentType = ConsentType.TERMS_OF_SERVICE,

    @Column(name = "granted_at", nullable = false)
    var grantedAt: Instant = Instant.now(),

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "ip_address", length = 45)
    var ipAddress: String? = null,

    @Column(name = "is_granted", nullable = false)
    var isGranted: Boolean = true,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
