package com.aesthetic.backend.usecase

import com.aesthetic.backend.dto.response.DashboardStatsResponse
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.tenant.TenantContext
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

@Service
class DashboardService(
    private val appointmentRepository: AppointmentRepository,
    private val siteSettingsService: SiteSettingsService
) {

    @Cacheable(value = ["dashboard-stats"], keyGenerator = "tenantCacheKeyGenerator")
    @Transactional(readOnly = true)
    fun getTodayStats(): DashboardStatsResponse {
        val tenantId = TenantContext.getTenantId()
        val settings = siteSettingsService.getSettings()
        val tenantZone = ZoneId.of(settings.timezone)
        val today = LocalDate.now(tenantZone)

        val row = appointmentRepository.getDailyStats(tenantId, today)

        return DashboardStatsResponse(
            date = today,
            totalAppointments = (row[0] as? Long) ?: 0,
            completed = (row[1] as? Long) ?: 0,
            pending = (row[2] as? Long) ?: 0,
            confirmed = (row[3] as? Long) ?: 0,
            cancelled = (row[4] as? Long) ?: 0,
            noShow = (row[5] as? Long) ?: 0,
            revenue = (row[6] as? BigDecimal) ?: BigDecimal.ZERO
        )
    }
}
