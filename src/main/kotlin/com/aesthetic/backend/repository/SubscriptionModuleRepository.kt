package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.domain.subscription.Subscription
import com.aesthetic.backend.domain.subscription.SubscriptionModule
import org.springframework.data.jpa.repository.JpaRepository

interface SubscriptionModuleRepository : JpaRepository<SubscriptionModule, String> {
    fun findBySubscriptionAndModuleAndIsActiveTrue(subscription: Subscription, module: FeatureModule): SubscriptionModule?
    fun findAllBySubscriptionAndIsActiveTrue(subscription: Subscription): List<SubscriptionModule>
    fun deleteAllBySubscription(subscription: Subscription)
}
