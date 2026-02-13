package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.settings.SiteSettings
import org.springframework.data.jpa.repository.JpaRepository

interface SiteSettingsRepository : JpaRepository<SiteSettings, String> {
    fun findByTenantId(tenantId: String): SiteSettings?
}
