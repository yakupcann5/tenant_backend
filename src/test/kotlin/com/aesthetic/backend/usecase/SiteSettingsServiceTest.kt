package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.settings.SiteSettings
import com.aesthetic.backend.dto.request.UpdateSiteSettingsRequest
import com.aesthetic.backend.repository.SiteSettingsRepository
import com.aesthetic.backend.tenant.TenantContext
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class SiteSettingsServiceTest {

    @MockK
    private lateinit var siteSettingsRepository: SiteSettingsRepository

    private lateinit var siteSettingsService: SiteSettingsService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        siteSettingsService = SiteSettingsService(siteSettingsRepository)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `getSettings should return existing settings`() {
        val settings = createSettings()
        every { siteSettingsRepository.findByTenantId(tenantId) } returns settings

        val result = siteSettingsService.getSettings()

        assertEquals("Test Salon", result.siteName)
        assertEquals("Europe/Istanbul", result.timezone)
        assertEquals("tr", result.locale)
        assertEquals(24, result.cancellationPolicyHours)
    }

    @Test
    fun `getSettings should auto-create default settings when none exists`() {
        every { siteSettingsRepository.findByTenantId(tenantId) } returns null
        every { siteSettingsRepository.save(any()) } answers {
            (firstArg() as SiteSettings).apply {
                val idField = SiteSettings::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "new-settings-id")
            }
        }

        val result = siteSettingsService.getSettings()

        assertNotNull(result)
        assertEquals("Europe/Istanbul", result.timezone)
        assertEquals("tr", result.locale)
        verify { siteSettingsRepository.save(any()) }
    }

    @Test
    fun `updateSettings should update provided fields only`() {
        val settings = createSettings()
        every { siteSettingsRepository.findByTenantId(tenantId) } returns settings
        every { siteSettingsRepository.save(any()) } answers { firstArg() }

        val request = UpdateSiteSettingsRequest(
            siteName = "Updated Salon",
            phone = "5559876543",
            cancellationPolicyHours = 48
        )

        val result = siteSettingsService.updateSettings(request)

        assertEquals("Updated Salon", result.siteName)
        assertEquals("5559876543", result.phone)
        assertEquals(48, result.cancellationPolicyHours)
        assertEquals("Europe/Istanbul", result.timezone) // unchanged
    }

    @Test
    fun `updateSettings should validate timezone`() {
        val settings = createSettings()
        every { siteSettingsRepository.findByTenantId(tenantId) } returns settings

        val request = UpdateSiteSettingsRequest(timezone = "Invalid/Timezone")

        assertThrows<IllegalArgumentException> {
            siteSettingsService.updateSettings(request)
        }
    }

    @Test
    fun `updateSettings should accept valid timezone`() {
        val settings = createSettings()
        every { siteSettingsRepository.findByTenantId(tenantId) } returns settings
        every { siteSettingsRepository.save(any()) } answers { firstArg() }

        val request = UpdateSiteSettingsRequest(timezone = "America/New_York")
        val result = siteSettingsService.updateSettings(request)

        assertEquals("America/New_York", result.timezone)
    }

    @Test
    fun `updateSettings should auto-create settings when none exists`() {
        every { siteSettingsRepository.findByTenantId(tenantId) } returns null
        every { siteSettingsRepository.save(any()) } answers {
            (firstArg() as SiteSettings).apply {
                val idField = SiteSettings::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "new-settings-id")
            }
        }

        val request = UpdateSiteSettingsRequest(siteName = "New Salon")
        val result = siteSettingsService.updateSettings(request)

        assertEquals("New Salon", result.siteName)
        verify(exactly = 2) { siteSettingsRepository.save(any()) } // once for create, once for update
    }

    private fun createSettings() = SiteSettings(
        id = "settings-1",
        siteName = "Test Salon",
        phone = "5551234567",
        email = "test@salon.com",
        timezone = "Europe/Istanbul",
        locale = "tr",
        cancellationPolicyHours = 24,
        defaultSlotDurationMinutes = 30
    ).apply { tenantId = this@SiteSettingsServiceTest.tenantId }
}
