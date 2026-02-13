package com.aesthetic.backend.domain.subscription

import com.aesthetic.backend.domain.tenant.SubscriptionPlan
import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(
    name = "subscriptions",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_subscriptions_tenant", columnNames = ["tenant_id"])
    ]
)
class Subscription(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var plan: SubscriptionPlan = SubscriptionPlan.TRIAL,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SubscriptionStatus = SubscriptionStatus.TRIAL,

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false)
    var billingPeriod: BillingPeriod = BillingPeriod.MONTHLY,

    @Column(name = "current_period_start")
    var currentPeriodStart: Instant? = null,

    @Column(name = "current_period_end")
    var currentPeriodEnd: Instant? = null,

    @Column(name = "trial_end_date")
    var trialEndDate: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "pending_plan_change")
    var pendingPlanChange: SubscriptionPlan? = null,

    @Column(name = "retry_count")
    var retryCount: Int = 0,

    @Column(name = "last_retry_at")
    var lastRetryAt: Instant? = null,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
