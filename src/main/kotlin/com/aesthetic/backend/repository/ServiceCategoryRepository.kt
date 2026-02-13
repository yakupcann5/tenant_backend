package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.service.ServiceCategory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ServiceCategoryRepository : JpaRepository<ServiceCategory, String> {
    fun findBySlugAndTenantId(slug: String, tenantId: String): ServiceCategory?

    @Query("SELECT c FROM ServiceCategory c WHERE c.tenantId = :tenantId ORDER BY c.sortOrder ASC, c.name ASC")
    fun findAllByTenantIdOrdered(tenantId: String): List<ServiceCategory>

    @Query("SELECT c FROM ServiceCategory c WHERE c.tenantId = :tenantId AND c.isActive = true ORDER BY c.sortOrder ASC, c.name ASC")
    fun findActiveByTenantIdOrdered(tenantId: String): List<ServiceCategory>

    @Query("SELECT c.id, COUNT(s) FROM ServiceCategory c LEFT JOIN Service s ON s.category = c AND s.tenantId = :tenantId WHERE c.tenantId = :tenantId GROUP BY c.id")
    fun countServicesByCategory(tenantId: String): List<Array<Any>>
}
