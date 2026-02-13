package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.subscription.SubscriptionStatus
import com.aesthetic.backend.dto.response.PlatformStatsResponse
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.repository.SubscriptionRepository
import com.aesthetic.backend.repository.TenantRepository
import com.aesthetic.backend.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PlatformStatsService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val appointmentRepository: AppointmentRepository,
    private val subscriptionRepository: SubscriptionRepository
) {

    @Transactional(readOnly = true)
    fun getStats(): PlatformStatsResponse {
        val totalTenants = tenantRepository.count()
        val activeTenants = tenantRepository.countByIsActiveTrue()
        val totalUsers = userRepository.count()
        val totalAppointments = appointmentRepository.count()
        val trialSubscriptions = subscriptionRepository.findAllByStatus(SubscriptionStatus.TRIAL)
        val activeSubscriptions = subscriptionRepository.findAllByStatus(SubscriptionStatus.ACTIVE)

        return PlatformStatsResponse(
            totalTenants = totalTenants,
            activeTenants = activeTenants,
            totalUsers = totalUsers,
            totalAppointments = totalAppointments,
            trialTenants = trialSubscriptions.size.toLong(),
            activePlanTenants = activeSubscriptions.size.toLong()
        )
    }
}
