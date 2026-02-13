package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.subscription.*
import com.aesthetic.backend.domain.tenant.SubscriptionPlan
import com.aesthetic.backend.dto.request.ChangePlanRequest
import com.aesthetic.backend.dto.response.SubscriptionResponse
import com.aesthetic.backend.exception.PlanLimitExceededException
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.SubscriptionModuleRepository
import com.aesthetic.backend.repository.SubscriptionRepository
import com.aesthetic.backend.repository.TenantRepository
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Service
class SubscriptionService(
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionModuleRepository: SubscriptionModuleRepository,
    private val tenantRepository: TenantRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getSubscription(): SubscriptionResponse {
        val tenantId = TenantContext.getTenantId()
        val subscription = subscriptionRepository.findByTenantId(tenantId)
            ?: throw ResourceNotFoundException("Abonelik bulunamadı")
        val modules = getActiveModules(subscription)
        return subscription.toResponse(modules)
    }

    @Transactional
    fun createTrialSubscription(tenantId: String): Subscription {
        val subscription = Subscription(
            plan = SubscriptionPlan.TRIAL,
            status = SubscriptionStatus.TRIAL,
            trialEndDate = LocalDate.now().plusDays(14)
        )
        subscription.tenantId = tenantId
        val saved = subscriptionRepository.save(subscription)
        logger.info("Trial subscription created for tenant={}", tenantId)
        return saved
    }

    @Transactional
    fun changePlan(request: ChangePlanRequest): SubscriptionResponse {
        val tenantId = TenantContext.getTenantId()
        val subscription = subscriptionRepository.findByTenantId(tenantId)
            ?: throw ResourceNotFoundException("Abonelik bulunamadı")

        val currentPlan = subscription.plan
        val newPlan = request.plan

        require(newPlan != SubscriptionPlan.TRIAL) { "TRIAL planına geçiş yapılamaz" }
        require(newPlan != currentPlan) { "Zaten bu plandanız" }

        val isUpgrade = newPlan.ordinal > currentPlan.ordinal

        if (isUpgrade) {
            subscription.plan = newPlan
            subscription.billingPeriod = request.billingPeriod
            subscription.pendingPlanChange = null
            syncTenantPlan(tenantId, newPlan)
            syncModulesForPlan(subscription, newPlan)
            logger.info("Plan upgraded: tenant={}, {} -> {}", tenantId, currentPlan, newPlan)
        } else {
            subscription.pendingPlanChange = newPlan
            logger.info("Plan downgrade scheduled: tenant={}, {} -> {} (next billing cycle)", tenantId, currentPlan, newPlan)
        }

        subscriptionRepository.save(subscription)
        val modules = getActiveModules(subscription)
        return subscription.toResponse(modules)
    }

    @Transactional
    fun cancelSubscription(): SubscriptionResponse {
        val tenantId = TenantContext.getTenantId()
        val subscription = subscriptionRepository.findByTenantId(tenantId)
            ?: throw ResourceNotFoundException("Abonelik bulunamadı")

        require(subscription.status in listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL, SubscriptionStatus.PAST_DUE)) {
            "Bu abonelik iptal edilemez"
        }

        subscription.status = SubscriptionStatus.CANCELLED
        subscriptionRepository.save(subscription)
        logger.info("Subscription cancelled: tenant={}", tenantId)

        val modules = getActiveModules(subscription)
        return subscription.toResponse(modules)
    }

    @Transactional
    fun activateSubscription(tenantId: String, plan: SubscriptionPlan, billingPeriod: BillingPeriod) {
        val subscription = subscriptionRepository.findByTenantId(tenantId)
            ?: throw ResourceNotFoundException("Abonelik bulunamadı")

        subscription.plan = plan
        subscription.status = SubscriptionStatus.ACTIVE
        subscription.billingPeriod = billingPeriod
        subscription.currentPeriodStart = Instant.now()
        subscription.currentPeriodEnd = when (billingPeriod) {
            BillingPeriod.MONTHLY -> Instant.now().plus(30, ChronoUnit.DAYS)
            BillingPeriod.YEARLY -> Instant.now().plus(365, ChronoUnit.DAYS)
        }
        subscription.retryCount = 0
        subscription.lastRetryAt = null

        syncModulesForPlan(subscription, plan)
        subscriptionRepository.save(subscription)
        syncTenantPlan(tenantId, plan)
        logger.info("Subscription activated: tenant={}, plan={}", tenantId, plan)
    }

    @Transactional
    fun handlePaymentSuccess(tenantId: String) {
        val subscription = subscriptionRepository.findByTenantId(tenantId)
            ?: throw ResourceNotFoundException("Abonelik bulunamadı")

        if (subscription.pendingPlanChange != null) {
            val newPlan = subscription.pendingPlanChange!!
            subscription.plan = newPlan
            subscription.pendingPlanChange = null
            syncModulesForPlan(subscription, newPlan)
            syncTenantPlan(tenantId, newPlan)
            logger.info("Pending plan change applied: tenant={}, plan={}", tenantId, newPlan)
        }

        subscription.status = SubscriptionStatus.ACTIVE
        subscription.currentPeriodStart = Instant.now()
        subscription.currentPeriodEnd = when (subscription.billingPeriod) {
            BillingPeriod.MONTHLY -> Instant.now().plus(30, ChronoUnit.DAYS)
            BillingPeriod.YEARLY -> Instant.now().plus(365, ChronoUnit.DAYS)
        }
        subscription.retryCount = 0
        subscription.lastRetryAt = null

        subscriptionRepository.save(subscription)
        logger.info("Payment success processed: tenant={}", tenantId)
    }

    @Transactional
    fun handlePaymentFailed(tenantId: String) {
        val subscription = subscriptionRepository.findByTenantId(tenantId)
            ?: throw ResourceNotFoundException("Abonelik bulunamadı")

        if (subscription.status == SubscriptionStatus.ACTIVE) {
            subscription.status = SubscriptionStatus.PAST_DUE
        }
        subscription.retryCount++
        subscription.lastRetryAt = Instant.now()

        if (subscription.retryCount >= 3) {
            subscription.status = SubscriptionStatus.EXPIRED
            logger.warn("Subscription expired after 3 retries: tenant={}", tenantId)
        }

        subscriptionRepository.save(subscription)
        logger.info("Payment failed processed: tenant={}, retryCount={}", tenantId, subscription.retryCount)
    }

    fun getActiveModules(subscription: Subscription): List<FeatureModule> {
        if (subscription.status == SubscriptionStatus.TRIAL) {
            return FeatureModule.entries.toList()
        }
        return subscriptionModuleRepository.findAllBySubscriptionAndIsActiveTrue(subscription)
            .map { it.module }
    }

    fun syncTenantPlan(tenantId: String, newPlan: SubscriptionPlan) {
        val tenant = tenantRepository.findById(tenantId)
            .orElseThrow { ResourceNotFoundException("Tenant bulunamadı") }
        tenant.plan = newPlan
        tenantRepository.save(tenant)
    }

    private fun syncModulesForPlan(subscription: Subscription, plan: SubscriptionPlan) {
        subscriptionModuleRepository.deleteAllBySubscription(subscription)

        val modules = getDefaultModulesForPlan(plan)
        modules.forEach { module ->
            val subModule = SubscriptionModule(
                subscription = subscription,
                module = module
            )
            subModule.tenantId = subscription.tenantId
            subscriptionModuleRepository.save(subModule)
        }
    }

    private fun getDefaultModulesForPlan(plan: SubscriptionPlan): List<FeatureModule> = when (plan) {
        SubscriptionPlan.TRIAL -> FeatureModule.entries.toList()
        SubscriptionPlan.STARTER -> listOf(
            FeatureModule.APPOINTMENTS,
            FeatureModule.CONTACT_MESSAGES
        )
        SubscriptionPlan.PROFESSIONAL -> listOf(
            FeatureModule.APPOINTMENTS,
            FeatureModule.CONTACT_MESSAGES,
            FeatureModule.BLOG,
            FeatureModule.GALLERY,
            FeatureModule.REVIEWS,
            FeatureModule.PRODUCTS
        )
        SubscriptionPlan.BUSINESS -> listOf(
            FeatureModule.APPOINTMENTS,
            FeatureModule.CONTACT_MESSAGES,
            FeatureModule.BLOG,
            FeatureModule.GALLERY,
            FeatureModule.REVIEWS,
            FeatureModule.PRODUCTS,
            FeatureModule.CLIENT_NOTES,
            FeatureModule.NOTIFICATIONS
        )
        SubscriptionPlan.ENTERPRISE -> FeatureModule.entries.toList()
    }
}
