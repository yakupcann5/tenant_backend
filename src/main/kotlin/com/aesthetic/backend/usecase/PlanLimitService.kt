package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.subscription.PlanLimits
import com.aesthetic.backend.domain.subscription.SubscriptionStatus
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.dto.response.PlanUsageResponse
import com.aesthetic.backend.exception.PlanLimitExceededException
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.repository.SubscriptionRepository
import com.aesthetic.backend.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Service
class PlanLimitService(
    private val subscriptionRepository: SubscriptionRepository,
    private val userRepository: UserRepository,
    private val appointmentRepository: AppointmentRepository
) {

    @Transactional(readOnly = true)
    fun checkCanCreateAppointment(tenantId: String) {
        val subscription = subscriptionRepository.findByTenantId(tenantId)
            ?: throw ResourceNotFoundException("Abonelik bulunamadı")

        val limits = PlanLimits.forPlan(subscription.plan)
        if (limits.maxAppointmentsPerMonth == Int.MAX_VALUE) return

        val startOfMonth = ZonedDateTime.now(ZoneOffset.UTC).withDayOfMonth(1)
            .withHour(0).withMinute(0).withSecond(0).withNano(0).toInstant()
        val currentCount = appointmentRepository.countByTenantIdAndCreatedAtAfter(tenantId, startOfMonth)

        if (currentCount >= limits.maxAppointmentsPerMonth) {
            throw PlanLimitExceededException(
                "Aylık randevu limiti doldu (${limits.maxAppointmentsPerMonth}). Planınızı yükseltin."
            )
        }
    }

    @Transactional(readOnly = true)
    fun checkCanCreateStaff(tenantId: String) {
        val subscription = subscriptionRepository.findByTenantId(tenantId)
            ?: throw ResourceNotFoundException("Abonelik bulunamadı")

        val limits = PlanLimits.forPlan(subscription.plan)
        if (limits.maxStaff == Int.MAX_VALUE) return

        val currentCount = userRepository.countByTenantIdAndRole(tenantId, Role.STAFF)

        if (currentCount >= limits.maxStaff) {
            throw PlanLimitExceededException(
                "Personel limiti doldu (${limits.maxStaff}). Planınızı yükseltin."
            )
        }
    }

    @Transactional(readOnly = true)
    fun getCurrentUsage(tenantId: String): PlanUsageResponse {
        val subscription = subscriptionRepository.findByTenantId(tenantId)
            ?: throw ResourceNotFoundException("Abonelik bulunamadı")

        val limits = PlanLimits.forPlan(subscription.plan)

        val staffCount = userRepository.countByTenantIdAndRole(tenantId, Role.STAFF)

        val startOfMonth = ZonedDateTime.now(ZoneOffset.UTC).withDayOfMonth(1)
            .withHour(0).withMinute(0).withSecond(0).withNano(0).toInstant()
        val appointmentCount = appointmentRepository.countByTenantIdAndCreatedAtAfter(tenantId, startOfMonth)

        return PlanUsageResponse(
            staffCount = staffCount,
            maxStaff = limits.maxStaff,
            appointmentCount = appointmentCount,
            maxAppointments = limits.maxAppointmentsPerMonth,
            storageMB = 0, // TODO: Dosya yükleme modülü implementé edildiğinde
            maxStorageMB = limits.maxStorageMB
        )
    }
}
