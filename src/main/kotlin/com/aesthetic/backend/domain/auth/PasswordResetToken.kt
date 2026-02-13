package com.aesthetic.backend.domain.auth

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

@Entity
@Table(name = "password_reset_tokens")
class PasswordResetToken(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: String,

    @Column(nullable = false, unique = true)
    val token: String,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "is_used")
    var isUsed: Boolean = false,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null
)
