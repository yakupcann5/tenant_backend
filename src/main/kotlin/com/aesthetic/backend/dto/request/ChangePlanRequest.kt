package com.aesthetic.backend.dto.request

import com.aesthetic.backend.domain.subscription.BillingPeriod
import com.aesthetic.backend.domain.tenant.SubscriptionPlan
import jakarta.validation.constraints.NotNull

data class ChangePlanRequest(
    @field:NotNull(message = "Plan seçimi zorunludur")
    val plan: SubscriptionPlan,

    @field:NotNull(message = "Faturalama periyodu zorunludur")
    val billingPeriod: BillingPeriod
)
