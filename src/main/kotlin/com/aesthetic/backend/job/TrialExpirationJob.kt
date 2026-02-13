package com.aesthetic.backend.job

import com.aesthetic.backend.domain.notification.NotificationType
import com.aesthetic.backend.domain.subscription.SubscriptionStatus
import com.aesthetic.backend.repository.SubscriptionRepository
import com.aesthetic.backend.repository.TenantRepository
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.tenant.TenantContext
import com.aesthetic.backend.usecase.NotificationService
import com.aesthetic.backend.usecase.SubscriptionService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Component
class TrialExpirationJob(
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionService: SubscriptionService,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 1 * * ?")
    @SchedulerLock(name = "trialExpirationJob", lockAtLeastFor = "30m", lockAtMostFor = "1h")
    @Transactional
    fun expireTrials() {
        val today = LocalDate.now()
        val expiredTrials = subscriptionRepository.findAllByStatusAndTrialEndDateBefore(
            SubscriptionStatus.TRIAL, today
        )

        logger.info("Trial expiration job: {} subscriptions to expire", expiredTrials.size)

        expiredTrials.forEach { subscription ->
            try {
                TenantContext.setTenantId(subscription.tenantId)

                subscription.status = SubscriptionStatus.EXPIRED
                subscriptionRepository.save(subscription)

                subscriptionService.syncTenantPlan(subscription.tenantId, subscription.plan)

                val tenant = tenantRepository.findById(subscription.tenantId).orElse(null)
                if (tenant != null) {
                    val admin = userRepository.findFirstByTenantIdAndRole(
                        subscription.tenantId,
                        com.aesthetic.backend.domain.user.Role.TENANT_ADMIN
                    )
                    if (admin != null) {
                        try {
                            val ctx = notificationService.toContext(admin, NotificationType.TRIAL_EXPIRING)
                            notificationService.sendNotification(ctx)
                        } catch (e: Exception) {
                            logger.error("Trial expiration notification failed: tenantId={}", subscription.tenantId, e)
                        }
                    }
                }

                logger.info("Trial expired: tenantId={}", subscription.tenantId)
            } catch (e: Exception) {
                logger.error("Trial expiration failed: tenantId={}", subscription.tenantId, e)
            } finally {
                TenantContext.clear()
            }
        }
    }
}
