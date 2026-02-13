package com.aesthetic.backend.domain.user

import com.aesthetic.backend.domain.schedule.BlockedTimeSlot
import com.aesthetic.backend.domain.schedule.WorkingHours
import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(
    name = "users",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_users_email_tenant", columnNames = ["email", "tenant_id"])
    ]
)
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "first_name", nullable = false, length = 100)
    var firstName: String,

    @Column(name = "last_name", nullable = false, length = 100)
    var lastName: String = "",

    @Column(nullable = false)
    var email: String,

    @Column(name = "password_hash")
    var passwordHash: String? = null,

    var phone: String = "",

    var image: String? = null,

    @Column(length = 100)
    var title: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: Role = Role.CLIENT,

    @Column(name = "is_active")
    var isActive: Boolean = true,

    @Column(name = "force_password_change")
    var forcePasswordChange: Boolean = false,

    @Column(name = "failed_login_attempts")
    var failedLoginAttempts: Int = 0,

    @Column(name = "locked_until")
    var lockedUntil: Instant? = null,

    @Column(name = "no_show_count")
    var noShowCount: Int = 0,

    @Column(name = "is_blacklisted")
    var isBlacklisted: Boolean = false,

    @Column(name = "blacklisted_at")
    var blacklistedAt: Instant? = null,

    @Column(name = "blacklist_reason")
    var blacklistReason: String? = null,

    @OneToMany(mappedBy = "staff")
    val workingHours: List<WorkingHours> = emptyList(),

    @OneToMany(mappedBy = "staff")
    val blockedTimeSlots: List<BlockedTimeSlot> = emptyList(),

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
