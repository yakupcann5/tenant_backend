package com.aesthetic.backend.job

import com.aesthetic.backend.repository.NotificationRepository
import com.aesthetic.backend.repository.PasswordResetTokenRepository
import com.aesthetic.backend.repository.RefreshTokenRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class CleanupJob(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val notificationRepository: NotificationRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 3 * * ?")
    @SchedulerLock(name = "cleanupJob", lockAtLeastFor = "30m", lockAtMostFor = "1h")
    @Transactional
    fun cleanup() {
        val now = Instant.now()

        try {
            refreshTokenRepository.deleteExpired(now)
            logger.debug("Expired refresh tokens cleaned up")
        } catch (e: Exception) {
            logger.error("Refresh token cleanup failed", e)
        }

        try {
            passwordResetTokenRepository.deleteExpired(now)
            logger.debug("Expired password reset tokens cleaned up")
        } catch (e: Exception) {
            logger.error("Password reset token cleanup failed", e)
        }

        try {
            val ninetyDaysAgo = now.minus(90, ChronoUnit.DAYS)
            notificationRepository.deleteByCreatedAtBefore(ninetyDaysAgo)
            logger.debug("Old notification logs cleaned up (90+ days)")
        } catch (e: Exception) {
            logger.error("Notification log cleanup failed", e)
        }

        logger.info("Cleanup job completed")
    }
}
