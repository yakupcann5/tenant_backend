package com.aesthetic.backend.usecase

import com.aesthetic.backend.dto.response.SiteSettingsResponse
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.tenant.TenantContext
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@ExtendWith(MockKExtension::class)
class DashboardServiceTest {

    @MockK private lateinit var appointmentRepository: AppointmentRepository
    @MockK private lateinit var siteSettingsService: SiteSettingsService

    private lateinit var service: DashboardService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        service = DashboardService(appointmentRepository, siteSettingsService)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `getTodayStats should return all zeros for empty day`() {
        every { siteSettingsService.getSettings() } returns createSettings()
        val today = LocalDate.now(ZoneId.of("Europe/Istanbul"))
        every { appointmentRepository.getDailyStats(tenantId, today) } returns arrayOf(
            0L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO
        )

        val result = service.getTodayStats()

        assertEquals(0L, result.totalAppointments)
        assertEquals(0L, result.completed)
        assertEquals(0L, result.pending)
        assertEquals(0L, result.confirmed)
        assertEquals(0L, result.cancelled)
        assertEquals(0L, result.noShow)
        assertEquals(BigDecimal.ZERO, result.revenue)
    }

    @Test
    fun `getTodayStats should return correct counts for mixed statuses`() {
        every { siteSettingsService.getSettings() } returns createSettings()
        val today = LocalDate.now(ZoneId.of("Europe/Istanbul"))
        every { appointmentRepository.getDailyStats(tenantId, today) } returns arrayOf(
            10L, 4L, 2L, 3L, 1L, 0L, BigDecimal("500.00")
        )

        val result = service.getTodayStats()

        assertEquals(10L, result.totalAppointments)
        assertEquals(4L, result.completed)
        assertEquals(2L, result.pending)
        assertEquals(3L, result.confirmed)
        assertEquals(1L, result.cancelled)
        assertEquals(0L, result.noShow)
        assertEquals(BigDecimal("500.00"), result.revenue)
    }

    @Test
    fun `getTodayStats should calculate revenue from completed only`() {
        every { siteSettingsService.getSettings() } returns createSettings()
        val today = LocalDate.now(ZoneId.of("Europe/Istanbul"))
        every { appointmentRepository.getDailyStats(tenantId, today) } returns arrayOf(
            5L, 3L, 1L, 1L, 0L, 0L, BigDecimal("300.00")
        )

        val result = service.getTodayStats()

        assertEquals(BigDecimal("300.00"), result.revenue)
    }

    @Test
    fun `getTodayStats should use tenant timezone`() {
        every { siteSettingsService.getSettings() } returns createSettings(timezone = "America/New_York")
        val todayNY = LocalDate.now(ZoneId.of("America/New_York"))
        every { appointmentRepository.getDailyStats(tenantId, todayNY) } returns arrayOf(
            0L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO
        )

        val result = service.getTodayStats()

        assertEquals(todayNY, result.date)
    }

    private fun createSettings(timezone: String = "Europe/Istanbul") = SiteSettingsResponse(
        id = "settings-1",
        siteName = "Test Salon",
        phone = "",
        email = "",
        address = "",
        whatsapp = "",
        instagram = "",
        facebook = "",
        twitter = "",
        youtube = "",
        mapEmbedUrl = "",
        timezone = timezone,
        locale = "tr",
        cancellationPolicyHours = 24,
        defaultSlotDurationMinutes = 30,
        autoConfirmAppointments = false,
        themeSettings = "{}",
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}
