package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.settings.SiteSettings
import com.aesthetic.backend.dto.request.UpdateSiteSettingsRequest
import com.aesthetic.backend.dto.response.SiteSettingsResponse
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.SiteSettingsRepository
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SiteSettingsService(
    private val siteSettingsRepository: SiteSettingsRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    @Cacheable(value = ["site-settings"], keyGenerator = "tenantCacheKeyGenerator")
    fun getSettings(): SiteSettingsResponse {
        val tenantId = TenantContext.getTenantId()
        val settings = siteSettingsRepository.findByTenantId(tenantId)
            ?: createDefaultSettings(tenantId)
        return settings.toResponse()
    }

    @Transactional
    @CacheEvict(value = ["site-settings"], keyGenerator = "tenantCacheKeyGenerator")
    fun updateSettings(request: UpdateSiteSettingsRequest): SiteSettingsResponse {
        val tenantId = TenantContext.getTenantId()
        val settings = siteSettingsRepository.findByTenantId(tenantId)
            ?: createDefaultSettings(tenantId)

        request.siteName?.let { settings.siteName = it }
        request.phone?.let { settings.phone = it }
        request.email?.let { settings.email = it }
        request.address?.let { settings.address = it }
        request.whatsapp?.let { settings.whatsapp = it }
        request.instagram?.let { settings.instagram = it }
        request.facebook?.let { settings.facebook = it }
        request.twitter?.let { settings.twitter = it }
        request.youtube?.let { settings.youtube = it }
        request.mapEmbedUrl?.let { settings.mapEmbedUrl = it }
        request.timezone?.let {
            require(isValidTimezone(it)) { "Geçersiz timezone: $it" }
            settings.timezone = it
        }
        request.locale?.let { settings.locale = it }
        request.cancellationPolicyHours?.let { settings.cancellationPolicyHours = it }
        request.defaultSlotDurationMinutes?.let { settings.defaultSlotDurationMinutes = it }
        request.autoConfirmAppointments?.let { settings.autoConfirmAppointments = it }
        request.themeSettings?.let { settings.themeSettings = it }

        val saved = siteSettingsRepository.save(settings)
        logger.debug("Site settings updated for tenant={}", tenantId)
        return saved.toResponse()
    }

    private fun createDefaultSettings(tenantId: String): SiteSettings {
        val settings = SiteSettings()
        // tenantId is set automatically by TenantEntityListener
        return siteSettingsRepository.save(settings)
    }

    private fun isValidTimezone(timezone: String): Boolean {
        return try {
            java.time.ZoneId.of(timezone)
            true
        } catch (e: Exception) {
            false
        }
    }
}
