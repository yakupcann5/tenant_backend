package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.product.Product
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ProductRepository : JpaRepository<Product, String> {
    fun findBySlugAndTenantId(slug: String, tenantId: String): Product?
    fun findAllByTenantId(tenantId: String, pageable: Pageable): Page<Product>
    fun findAllByTenantIdAndIsActiveTrue(tenantId: String, pageable: Pageable): Page<Product>
}
