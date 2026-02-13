package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.service.Service
import com.aesthetic.backend.domain.service.ServiceCategory
import com.aesthetic.backend.dto.request.CreateServiceRequest
import com.aesthetic.backend.dto.request.UpdateServiceRequest
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.ServiceResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.ServiceRepository
import com.aesthetic.backend.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.Pageable
import org.springframework.transaction.annotation.Transactional

@org.springframework.stereotype.Service
class ServiceManagementService(
    private val serviceRepository: ServiceRepository,
    private val entityManager: EntityManager
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    @CacheEvict(value = ["services"], keyGenerator = "tenantCacheKeyGenerator")
    fun create(request: CreateServiceRequest): ServiceResponse {
        val tenantId = TenantContext.getTenantId()

        val existing = serviceRepository.findBySlugAndTenantId(request.slug, tenantId)
        if (existing != null) {
            throw IllegalArgumentException("Bu slug zaten kullanılıyor: ${request.slug}")
        }

        val service = Service(
            slug = request.slug,
            title = request.title,
            shortDescription = request.shortDescription,
            description = request.description,
            price = request.price,
            currency = request.currency,
            durationMinutes = request.durationMinutes,
            bufferMinutes = request.bufferMinutes,
            image = request.image,
            recovery = request.recovery,
            metaTitle = request.metaTitle,
            metaDescription = request.metaDescription,
            ogImage = request.ogImage
        )

        request.categoryId?.let {
            service.category = entityManager.getReference(ServiceCategory::class.java, it)
        }

        service.benefits = request.benefits.toMutableList()
        service.processSteps = request.processSteps.toMutableList()

        val saved = serviceRepository.save(service)
        logger.debug("Service created: id={}, tenant={}", saved.id, tenantId)

        return saved.toResponse()
    }

    @Transactional(readOnly = true)
    @Cacheable(value = ["services"], keyGenerator = "tenantCacheKeyGenerator")
    fun listAll(pageable: Pageable): PagedResponse<ServiceResponse> {
        val tenantId = TenantContext.getTenantId()
        return serviceRepository.findAllByTenantId(tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun listActive(pageable: Pageable): PagedResponse<ServiceResponse> {
        val tenantId = TenantContext.getTenantId()
        return serviceRepository.findActiveByTenantId(tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun getById(id: String): ServiceResponse {
        val service = serviceRepository.findByIdWithCategory(id)
            ?: throw ResourceNotFoundException("Hizmet bulunamadı: $id")
        return service.toResponse()
    }

    @Transactional(readOnly = true)
    fun getBySlug(slug: String): ServiceResponse {
        val tenantId = TenantContext.getTenantId()
        val service = serviceRepository.findBySlugWithCategory(slug, tenantId)
            ?: throw ResourceNotFoundException("Hizmet bulunamadı: $slug")
        return service.toResponse()
    }

    @Transactional
    @CacheEvict(value = ["services"], keyGenerator = "tenantCacheKeyGenerator")
    fun update(id: String, request: UpdateServiceRequest): ServiceResponse {
        val tenantId = TenantContext.getTenantId()
        val service = serviceRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Hizmet bulunamadı: $id") }

        request.slug?.let { newSlug ->
            if (newSlug != service.slug) {
                val existing = serviceRepository.findBySlugAndTenantId(newSlug, tenantId)
                if (existing != null) {
                    throw IllegalArgumentException("Bu slug zaten kullanılıyor: $newSlug")
                }
                service.slug = newSlug
            }
        }
        request.title?.let { service.title = it }
        request.shortDescription?.let { service.shortDescription = it }
        request.description?.let { service.description = it }
        request.price?.let { service.price = it }
        request.currency?.let { service.currency = it }
        request.durationMinutes?.let { service.durationMinutes = it }
        request.bufferMinutes?.let { service.bufferMinutes = it }
        request.image?.let { service.image = it }
        request.recovery?.let { service.recovery = it }
        request.isActive?.let { service.isActive = it }
        request.sortOrder?.let { service.sortOrder = it }
        request.metaTitle?.let { service.metaTitle = it }
        request.metaDescription?.let { service.metaDescription = it }
        request.ogImage?.let { service.ogImage = it }
        request.benefits?.let { service.benefits = it.toMutableList() }
        request.processSteps?.let { service.processSteps = it.toMutableList() }

        if (request.removeCategoryId) {
            service.category = null
        } else {
            request.categoryId?.let {
                service.category = entityManager.getReference(ServiceCategory::class.java, it)
            }
        }

        val saved = serviceRepository.save(service)
        logger.debug("Service updated: id={}, tenant={}", id, tenantId)
        return saved.toResponse()
    }

    @Transactional
    @CacheEvict(value = ["services"], keyGenerator = "tenantCacheKeyGenerator")
    fun delete(id: String) {
        val service = serviceRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Hizmet bulunamadı: $id") }
        serviceRepository.delete(service)
        logger.debug("Service deleted: id={}", id)
    }
}
