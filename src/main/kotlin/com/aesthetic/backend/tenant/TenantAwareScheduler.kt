package com.aesthetic.backend.tenant

import com.aesthetic.backend.domain.tenant.Tenant
import com.aesthetic.backend.repository.TenantRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class TenantAwareScheduler(
    private val tenantRepository: TenantRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun executeForAllTenants(action: (Tenant) -> Unit) {
        val tenants = tenantRepository.findAllByIsActiveTrue()
        for (tenant in tenants) {
            try {
                TenantContext.setTenantId(tenant.id!!)
                action(tenant)
            } catch (e: Exception) {
                logger.error("[tenant={}] Scheduled job hatası: {}", tenant.slug, e.message, e)
            } finally {
                TenantContext.clear()
            }
        }
    }
}
