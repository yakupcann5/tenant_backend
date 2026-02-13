package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.gallery.GalleryItem
import com.aesthetic.backend.dto.request.CreateGalleryItemRequest
import com.aesthetic.backend.dto.request.UpdateGalleryItemRequest
import com.aesthetic.backend.dto.response.GalleryItemResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.GalleryItemRepository
import com.aesthetic.backend.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GalleryService(
    private val galleryItemRepository: GalleryItemRepository,
    private val entityManager: EntityManager
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(request: CreateGalleryItemRequest): GalleryItemResponse {
        val item = GalleryItem(
            title = request.title,
            description = request.description,
            imageUrl = request.imageUrl,
            beforeImageUrl = request.beforeImageUrl,
            afterImageUrl = request.afterImageUrl,
            sortOrder = request.sortOrder,
            category = request.category
        )
        request.serviceId?.let {
            item.service = entityManager.getReference(com.aesthetic.backend.domain.service.Service::class.java, it)
        }
        return galleryItemRepository.save(item).toResponse()
    }

    @Transactional
    fun update(id: String, request: UpdateGalleryItemRequest): GalleryItemResponse {
        val item = galleryItemRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Galeri öğesi bulunamadı: $id") }

        request.title?.let { item.title = it }
        request.description?.let { item.description = it }
        request.imageUrl?.let { item.imageUrl = it }
        request.beforeImageUrl?.let { item.beforeImageUrl = it }
        request.afterImageUrl?.let { item.afterImageUrl = it }
        request.isActive?.let { item.isActive = it }
        request.sortOrder?.let { item.sortOrder = it }
        request.category?.let { item.category = it }
        if (request.removeService) {
            item.service = null
        } else {
            request.serviceId?.let {
                item.service = entityManager.getReference(com.aesthetic.backend.domain.service.Service::class.java, it)
            }
        }

        return galleryItemRepository.save(item).toResponse()
    }

    @Transactional
    fun delete(id: String) {
        val item = galleryItemRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Galeri öğesi bulunamadı: $id") }
        galleryItemRepository.delete(item)
    }

    @Transactional(readOnly = true)
    fun getById(id: String): GalleryItemResponse {
        return galleryItemRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Galeri öğesi bulunamadı: $id") }
            .toResponse()
    }

    @Transactional(readOnly = true)
    fun listAll(pageable: Pageable): PagedResponse<GalleryItemResponse> {
        val tenantId = TenantContext.getTenantId()
        return galleryItemRepository.findAllByTenantId(tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun listActive(pageable: Pageable): PagedResponse<GalleryItemResponse> {
        val tenantId = TenantContext.getTenantId()
        return galleryItemRepository.findAllByTenantIdAndIsActiveTrue(tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }
}
