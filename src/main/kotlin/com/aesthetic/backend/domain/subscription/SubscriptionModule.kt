package com.aesthetic.backend.domain.subscription

import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

@Entity
@Table(
    name = "subscription_modules",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_sub_modules_subscription_module", columnNames = ["subscription_id", "module"])
    ]
)
class SubscriptionModule(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    var subscription: Subscription,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var module: FeatureModule,

    @Column(name = "is_active")
    var isActive: Boolean = true,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null
) : TenantAwareEntity()
