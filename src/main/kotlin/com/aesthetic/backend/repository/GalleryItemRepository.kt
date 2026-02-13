package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.gallery.GalleryItem
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface GalleryItemRepository : JpaRepository<GalleryItem, String> {
    fun findAllByTenantId(tenantId: String, pageable: Pageable): Page<GalleryItem>
    fun findAllByTenantIdAndIsActiveTrue(tenantId: String, pageable: Pageable): Page<GalleryItem>
}
