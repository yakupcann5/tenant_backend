package com.aesthetic.backend.dto.response

import com.aesthetic.backend.domain.subscription.BillingPeriod
import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.domain.subscription.SubscriptionStatus
import com.aesthetic.backend.domain.tenant.SubscriptionPlan
import java.time.Instant
import java.time.LocalDate

data class SubscriptionResponse(
    val id: String,
    val plan: SubscriptionPlan,
    val status: SubscriptionStatus,
    val billingPeriod: BillingPeriod,
    val currentPeriodEnd: Instant?,
    val trialEndDate: LocalDate?,
    val pendingPlanChange: SubscriptionPlan?,
    val enabledModules: List<FeatureModule>
)
