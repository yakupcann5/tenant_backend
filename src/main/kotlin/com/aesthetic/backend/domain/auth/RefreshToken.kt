package com.aesthetic.backend.domain.auth

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
    @Id
    val id: String,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: String,

    @Column(nullable = false)
    val family: String,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "is_revoked")
    var isRevoked: Boolean = false,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null
)
