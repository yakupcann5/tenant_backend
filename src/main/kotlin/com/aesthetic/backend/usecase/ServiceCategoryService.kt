package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.service.ServiceCategory
import com.aesthetic.backend.dto.request.CreateServiceCategoryRequest
import com.aesthetic.backend.dto.request.UpdateServiceCategoryRequest
import com.aesthetic.backend.dto.response.ServiceCategoryResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.ServiceCategoryRepository
import com.aesthetic.backend.repository.ServiceRepository
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ServiceCategoryService(
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val serviceRepository: ServiceRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    @CacheEvict(value = ["services"], keyGenerator = "tenantCacheKeyGenerator")
    fun create(request: CreateServiceCategoryRequest): ServiceCategoryResponse {
        val tenantId = TenantContext.getTenantId()

        val existing = serviceCategoryRepository.findBySlugAndTenantId(request.slug, tenantId)
        if (existing != null) {
            throw IllegalArgumentException("Bu slug zaten kullanılıyor: ${request.slug}")
        }

        val category = ServiceCategory(
            slug = request.slug,
            name = request.name,
            description = request.description,
            image = request.image
        )
        val saved = serviceCategoryRepository.save(category)
        logger.debug("Service category created: id={}, tenant={}", saved.id, tenantId)
        return saved.toResponse(serviceCount = 0)
    }

    @Transactional(readOnly = true)
    @Cacheable(value = ["services"], keyGenerator = "tenantCacheKeyGenerator")
    fun listAll(): List<ServiceCategoryResponse> {
        val tenantId = TenantContext.getTenantId()
        val categories = serviceCategoryRepository.findAllByTenantIdOrdered(tenantId)
        val countMap = getServiceCountMap(tenantId)
        return categories.map { it.toResponse(serviceCount = countMap[it.id] ?: 0) }
    }

    @Transactional(readOnly = true)
    fun listActive(): List<ServiceCategoryResponse> {
        val tenantId = TenantContext.getTenantId()
        val categories = serviceCategoryRepository.findActiveByTenantIdOrdered(tenantId)
        val countMap = getServiceCountMap(tenantId)
        return categories.map { it.toResponse(serviceCount = countMap[it.id] ?: 0) }
    }

    private fun getServiceCountMap(tenantId: String): Map<String, Long> {
        return serviceCategoryRepository.countServicesByCategory(tenantId)
            .associate { row -> (row[0] as String) to (row[1] as Long) }
    }

    @Transactional(readOnly = true)
    fun getById(id: String): ServiceCategoryResponse {
        val category = serviceCategoryRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Kategori bulunamadı: $id") }
        return category.toResponse(serviceCount = serviceRepository.countByCategoryId(id))
    }

    @Transactional
    @CacheEvict(value = ["services"], keyGenerator = "tenantCacheKeyGenerator")
    fun update(id: String, request: UpdateServiceCategoryRequest): ServiceCategoryResponse {
        val tenantId = TenantContext.getTenantId()
        val category = serviceCategoryRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Kategori bulunamadı: $id") }

        request.slug?.let { newSlug ->
            if (newSlug != category.slug) {
                val existing = serviceCategoryRepository.findBySlugAndTenantId(newSlug, tenantId)
                if (existing != null) {
                    throw IllegalArgumentException("Bu slug zaten kullanılıyor: $newSlug")
                }
                category.slug = newSlug
            }
        }
        request.name?.let { category.name = it }
        request.description?.let { category.description = it }
        request.image?.let { category.image = it }
        request.isActive?.let { category.isActive = it }
        request.sortOrder?.let { category.sortOrder = it }

        val saved = serviceCategoryRepository.save(category)
        logger.debug("Service category updated: id={}, tenant={}", id, tenantId)
        return saved.toResponse(serviceCount = serviceRepository.countByCategoryId(id))
    }

    @Transactional
    @CacheEvict(value = ["services"], keyGenerator = "tenantCacheKeyGenerator")
    fun delete(id: String) {
        val category = serviceCategoryRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Kategori bulunamadı: $id") }
        serviceCategoryRepository.delete(category)
        logger.debug("Service category deleted: id={}", id)
    }
}
