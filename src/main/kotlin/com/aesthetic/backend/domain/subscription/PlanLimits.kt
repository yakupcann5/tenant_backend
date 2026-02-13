package com.aesthetic.backend.domain.subscription

import com.aesthetic.backend.domain.tenant.SubscriptionPlan

data class PlanLimits(
    val maxStaff: Int,
    val maxAppointmentsPerMonth: Int,
    val maxStorageMB: Int
) {
    companion object {
        fun forPlan(plan: SubscriptionPlan): PlanLimits = when (plan) {
            SubscriptionPlan.TRIAL -> PlanLimits(maxStaff = 1, maxAppointmentsPerMonth = 100, maxStorageMB = 500)
            SubscriptionPlan.STARTER -> PlanLimits(maxStaff = 1, maxAppointmentsPerMonth = 100, maxStorageMB = 500)
            SubscriptionPlan.PROFESSIONAL -> PlanLimits(maxStaff = 5, maxAppointmentsPerMonth = 500, maxStorageMB = 2048)
            SubscriptionPlan.BUSINESS -> PlanLimits(maxStaff = 15, maxAppointmentsPerMonth = 2000, maxStorageMB = 5120)
            SubscriptionPlan.ENTERPRISE -> PlanLimits(maxStaff = Int.MAX_VALUE, maxAppointmentsPerMonth = Int.MAX_VALUE, maxStorageMB = Int.MAX_VALUE)
        }
    }
}
