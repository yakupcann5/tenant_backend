package com.aesthetic.backend.dto.response

import com.aesthetic.backend.domain.tenant.BusinessType
import com.aesthetic.backend.domain.tenant.SubscriptionPlan
import java.time.Instant
import java.time.LocalDate

data class TenantResponse(
    val id: String,
    val slug: String,
    val name: String,
    val businessType: BusinessType,
    val phone: String,
    val email: String,
    val plan: SubscriptionPlan,
    val trialEndDate: LocalDate?,
    val isActive: Boolean,
    val createdAt: Instant?
)
