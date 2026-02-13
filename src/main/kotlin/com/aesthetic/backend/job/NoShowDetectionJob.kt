package com.aesthetic.backend.job

import com.aesthetic.backend.domain.appointment.AppointmentStatus
import com.aesthetic.backend.domain.notification.NotificationType
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.repository.SiteSettingsRepository
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.tenant.TenantAwareScheduler
import com.aesthetic.backend.usecase.NotificationService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.time.ZonedDateTime

@Component
class NoShowDetectionJob(
    private val tenantAwareScheduler: TenantAwareScheduler,
    private val appointmentRepository: AppointmentRepository,
    private val siteSettingsRepository: SiteSettingsRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val NO_SHOW_BLACKLIST_THRESHOLD = 3
    }

    @Scheduled(fixedRate = 600000)
    @SchedulerLock(name = "noShowDetectionJob", lockAtLeastFor = "9m", lockAtMostFor = "15m")
    @Transactional
    fun detectNoShows() {
        tenantAwareScheduler.executeForAllTenants { tenant ->
            try {
                val settings = siteSettingsRepository.findByTenantId(tenant.id!!)
                val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")
                val now = ZonedDateTime.now(tenantZone)

                val cutoffDateTime = now.minusHours(1)
                val cutoffDate = cutoffDateTime.toLocalDate()
                val cutoffTime = cutoffDateTime.toLocalTime()

                val pastAppointments = appointmentRepository.findConfirmedPastAppointments(
                    tenant.id!!, cutoffDate, cutoffTime
                )

                pastAppointments.forEach { appt ->
                    try {
                        appt.status = AppointmentStatus.NO_SHOW

                        val client = appt.client
                        if (client != null) {
                            client.noShowCount++
                            if (client.noShowCount >= NO_SHOW_BLACKLIST_THRESHOLD) {
                                client.isBlacklisted = true
                                client.blacklistedAt = java.time.Instant.now()
                                client.blacklistReason =
                                    "Otomatik: $NO_SHOW_BLACKLIST_THRESHOLD kez randevuya gelmedi"
                                logger.warn(
                                    "[tenant={}] Client blacklisted: userId={}, noShowCount={}",
                                    tenant.slug, client.id, client.noShowCount
                                )
                                try {
                                    val ctx = notificationService.toContext(client, NotificationType.BLACKLIST)
                                    notificationService.sendNotification(ctx)
                                } catch (e: Exception) {
                                    logger.error("[tenant={}] Blacklist notification failed: userId={}", tenant.slug, client.id, e)
                                }
                            }
                            userRepository.save(client)
                        }

                        appointmentRepository.save(appt)

                        try {
                            val ctx = notificationService.toContext(appt)
                                .copy(notificationType = NotificationType.NO_SHOW_WARNING)
                            notificationService.sendNotification(ctx)
                        } catch (e: Exception) {
                            logger.error("[tenant={}] No-show notification failed: appt={}", tenant.slug, appt.id, e)
                        }

                        logger.debug("[tenant={}] No-show detected: appt={}", tenant.slug, appt.id)
                    } catch (e: Exception) {
                        logger.error("[tenant={}] No-show processing failed for appt={}", tenant.slug, appt.id, e)
                    }
                }
            } catch (e: Exception) {
                logger.error("[tenant={}] No-show detection job failed", tenant.slug, e)
            }
        }
    }
}
