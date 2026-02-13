package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.domain.subscription.Subscription
import com.aesthetic.backend.dto.response.SubscriptionResponse

fun Subscription.toResponse(enabledModules: List<FeatureModule>): SubscriptionResponse = SubscriptionResponse(
    id = id!!,
    plan = plan,
    status = status,
    billingPeriod = billingPeriod,
    currentPeriodEnd = currentPeriodEnd,
    trialEndDate = trialEndDate,
    pendingPlanChange = pendingPlanChange,
    enabledModules = enabledModules
)
