package com.aesthetic.backend.job

import com.aesthetic.backend.domain.subscription.SubscriptionStatus
import com.aesthetic.backend.repository.SubscriptionRepository
import com.aesthetic.backend.tenant.TenantContext
import com.aesthetic.backend.usecase.SubscriptionService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class SubscriptionRenewalJob(
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionService: SubscriptionService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 2 * * ?")
    @SchedulerLock(name = "subscriptionRenewalJob", lockAtLeastFor = "30m", lockAtMostFor = "1h")
    @Transactional
    fun retryFailedPayments() {
        val pastDueSubscriptions = subscriptionRepository.findAllByStatusAndRetryCountLessThan(
            SubscriptionStatus.PAST_DUE, 3
        )

        logger.info("Subscription renewal job: {} past-due subscriptions to retry", pastDueSubscriptions.size)

        pastDueSubscriptions.forEach { subscription ->
            try {
                TenantContext.setTenantId(subscription.tenantId)

                subscription.retryCount++
                subscription.lastRetryAt = Instant.now()

                if (subscription.retryCount >= 3) {
                    subscription.status = SubscriptionStatus.EXPIRED
                    subscriptionService.syncTenantPlan(subscription.tenantId, subscription.plan)
                    logger.warn(
                        "Subscription expired after 3 retries: tenantId={}",
                        subscription.tenantId
                    )
                }

                subscriptionRepository.save(subscription)
            } catch (e: Exception) {
                logger.error("Subscription renewal retry failed: tenantId={}", subscription.tenantId, e)
            } finally {
                TenantContext.clear()
            }
        }
    }
}
