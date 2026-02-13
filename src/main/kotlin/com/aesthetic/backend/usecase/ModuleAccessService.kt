package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.domain.subscription.SubscriptionStatus
import com.aesthetic.backend.exception.PlanLimitExceededException
import com.aesthetic.backend.repository.SubscriptionModuleRepository
import com.aesthetic.backend.repository.SubscriptionRepository
import org.springframework.stereotype.Service

@Service
class ModuleAccessService(
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionModuleRepository: SubscriptionModuleRepository
) {

    fun hasAccess(tenantId: String, module: FeatureModule): Boolean {
        val subscription = subscriptionRepository.findByTenantId(tenantId) ?: return false
        return when (subscription.status) {
            SubscriptionStatus.TRIAL -> true
            SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE ->
                subscriptionModuleRepository.findBySubscriptionAndModuleAndIsActiveTrue(subscription, module) != null
            else -> false
        }
    }

    fun requireAccess(tenantId: String, module: FeatureModule) {
        if (!hasAccess(tenantId, module)) {
            throw PlanLimitExceededException(
                "Bu özellik mevcut planınıza dahil değil: ${module.name}"
            )
        }
    }
}
