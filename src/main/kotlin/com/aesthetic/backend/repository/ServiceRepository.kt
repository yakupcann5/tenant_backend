package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.service.Service
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ServiceRepository : JpaRepository<Service, String> {
    fun findBySlugAndTenantId(slug: String, tenantId: String): Service?

    @Query("SELECT s FROM Service s WHERE s.tenantId = :tenantId")
    fun findAllByTenantId(tenantId: String, pageable: Pageable): Page<Service>

    @Query("SELECT s FROM Service s WHERE s.tenantId = :tenantId AND s.isActive = true")
    fun findActiveByTenantId(tenantId: String, pageable: Pageable): Page<Service>

    @Query(
        "SELECT s FROM Service s LEFT JOIN FETCH s.category WHERE s.id = :id"
    )
    fun findByIdWithCategory(id: String): Service?

    @Query(
        "SELECT s FROM Service s LEFT JOIN FETCH s.category WHERE s.slug = :slug AND s.tenantId = :tenantId"
    )
    fun findBySlugWithCategory(slug: String, tenantId: String): Service?

    fun countByCategoryId(categoryId: String): Long
}
