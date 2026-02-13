package com.aesthetic.backend.domain.tenant

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "tenants")
class Tenant(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(unique = true, nullable = false)
    val slug: String,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false)
    var businessType: BusinessType,

    var phone: String = "",
    var email: String = "",

    @Column(columnDefinition = "TEXT")
    var address: String = "",

    @Column(name = "logo_url")
    var logoUrl: String? = null,

    @Column(name = "custom_domain", unique = true)
    var customDomain: String? = null,

    @Enumerated(EnumType.STRING)
    var plan: SubscriptionPlan = SubscriptionPlan.TRIAL,

    @Column(name = "trial_end_date")
    var trialEndDate: LocalDate? = null,

    @Column(name = "is_active")
    var isActive: Boolean = true,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
)
