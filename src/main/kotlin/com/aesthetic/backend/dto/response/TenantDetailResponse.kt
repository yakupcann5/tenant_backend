package com.aesthetic.backend.dto.response

import com.aesthetic.backend.domain.tenant.BusinessType
import com.aesthetic.backend.domain.tenant.SubscriptionPlan
import com.aesthetic.backend.domain.subscription.SubscriptionStatus
import java.time.Instant
import java.time.LocalDate

data class TenantDetailResponse(
    val id: String,
    val slug: String,
    val name: String,
    val businessType: BusinessType,
    val phone: String,
    val email: String,
    val address: String,
    val logoUrl: String?,
    val customDomain: String?,
    val plan: SubscriptionPlan,
    val trialEndDate: LocalDate?,
    val isActive: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val subscriptionStatus: SubscriptionStatus?,
    val staffCount: Long,
    val clientCount: Long,
    val appointmentCount: Long,
    val reviewCount: Long,
    val averageRating: Double
)
