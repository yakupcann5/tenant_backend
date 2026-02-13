package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.tenant.Tenant
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TenantRepository : JpaRepository<Tenant, String> {
    fun findBySlugAndIsActiveTrue(slug: String): Tenant?
    fun findByCustomDomainAndIsActiveTrue(customDomain: String): Tenant?
    fun findAllByIsActiveTrue(): List<Tenant>
    fun countByIsActiveTrue(): Long
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<Tenant>

    @Query("SELECT t.customDomain FROM Tenant t WHERE t.customDomain IS NOT NULL")
    fun findAllCustomDomains(): List<String>
}
