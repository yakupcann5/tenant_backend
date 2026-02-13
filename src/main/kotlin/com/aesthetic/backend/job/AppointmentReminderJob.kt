package com.aesthetic.backend.job

import com.aesthetic.backend.domain.notification.NotificationType
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.repository.SiteSettingsRepository
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
class AppointmentReminderJob(
    private val tenantAwareScheduler: TenantAwareScheduler,
    private val appointmentRepository: AppointmentRepository,
    private val siteSettingsRepository: SiteSettingsRepository,
    private val notificationService: NotificationService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRate = 300000)
    @SchedulerLock(name = "appointmentReminderJob", lockAtLeastFor = "4m", lockAtMostFor = "10m")
    @Transactional
    fun sendReminders() {
        tenantAwareScheduler.executeForAllTenants { tenant ->
            try {
                val settings = siteSettingsRepository.findByTenantId(tenant.id!!)
                val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")
                val now = ZonedDateTime.now(tenantZone)

                send24hReminders(tenant.id!!, now, tenantZone)
                send1hReminders(tenant.id!!, now, tenantZone)
            } catch (e: Exception) {
                logger.error("[tenant={}] Reminder job failed", tenant.slug, e)
            }
        }
    }

    private fun send24hReminders(tenantId: String, now: ZonedDateTime, zone: ZoneId) {
        val tomorrow = now.plusHours(24)
        val windowStart = tomorrow.minusMinutes(5).toLocalTime()
        val windowEnd = tomorrow.plusMinutes(5).toLocalTime()
        val targetDate = tomorrow.toLocalDate()

        val appointments = appointmentRepository.findUnsentReminders24h(
            tenantId, targetDate, windowStart, windowEnd
        )

        if (appointments.isEmpty()) return

        val sentIds = mutableListOf<String>()
        appointments.forEach { appt ->
            try {
                val ctx = notificationService.toContext(appt)
                    .copy(notificationType = NotificationType.REMINDER_24H)
                notificationService.sendAppointmentReminder(ctx)
                sentIds.add(appt.id!!)
            } catch (e: Exception) {
                logger.error("[tenant={}] 24h reminder failed for appt={}", tenantId, appt.id, e)
            }
        }

        if (sentIds.isNotEmpty()) {
            appointmentRepository.markReminder24hSent(sentIds)
            logger.debug("[tenant={}] 24h reminders sent: {}", tenantId, sentIds.size)
        }
    }

    private fun send1hReminders(tenantId: String, now: ZonedDateTime, zone: ZoneId) {
        val oneHourLater = now.plusHours(1)
        val windowStart = oneHourLater.minusMinutes(5).toLocalTime()
        val windowEnd = oneHourLater.plusMinutes(5).toLocalTime()
        val targetDate = oneHourLater.toLocalDate()

        val appointments = appointmentRepository.findUnsentReminders1h(
            tenantId, targetDate, windowStart, windowEnd
        )

        if (appointments.isEmpty()) return

        val sentIds = mutableListOf<String>()
        appointments.forEach { appt ->
            try {
                val ctx = notificationService.toContext(appt)
                    .copy(notificationType = NotificationType.REMINDER_1H)
                notificationService.sendAppointmentReminder(ctx)
                sentIds.add(appt.id!!)
            } catch (e: Exception) {
                logger.error("[tenant={}] 1h reminder failed for appt={}", tenantId, appt.id, e)
            }
        }

        if (sentIds.isNotEmpty()) {
            appointmentRepository.markReminder1hSent(sentIds)
            logger.debug("[tenant={}] 1h reminders sent: {}", tenantId, sentIds.size)
        }
    }
}
