package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.TenantDetailResponse
import com.aesthetic.backend.dto.response.TenantResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.*
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TenantPlatformService(
    private val tenantRepository: TenantRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val userRepository: UserRepository,
    private val appointmentRepository: AppointmentRepository,
    private val reviewRepository: ReviewRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun listAll(pageable: Pageable): PagedResponse<TenantResponse> {
        return tenantRepository.findAllByOrderByCreatedAtDesc(pageable)
            .toPagedResponse { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun getDetail(id: String): TenantDetailResponse {
        val tenant = tenantRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Tenant bulunamadı: $id") }

        val subscription = subscriptionRepository.findByTenantId(id)
        val staffCount = userRepository.countByTenantIdAndRole(id, Role.STAFF)
        val clientCount = userRepository.countByTenantIdAndRole(id, Role.CLIENT)
        val reviewCount = reviewRepository.countByTenantId(id)
        val averageRating = reviewRepository.averageRatingByTenantId(id) ?: 0.0

        return TenantDetailResponse(
            id = tenant.id!!,
            slug = tenant.slug,
            name = tenant.name,
            businessType = tenant.businessType,
            phone = tenant.phone,
            email = tenant.email,
            address = tenant.address,
            logoUrl = tenant.logoUrl,
            customDomain = tenant.customDomain,
            plan = tenant.plan,
            trialEndDate = tenant.trialEndDate,
            isActive = tenant.isActive,
            createdAt = tenant.createdAt,
            updatedAt = tenant.updatedAt,
            subscriptionStatus = subscription?.status,
            staffCount = staffCount,
            clientCount = clientCount,
            appointmentCount = 0,
            reviewCount = reviewCount,
            averageRating = averageRating
        )
    }

    @Transactional
    fun activate(id: String): TenantResponse {
        val tenant = tenantRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Tenant bulunamadı: $id") }

        tenant.isActive = true
        logger.info("Tenant aktifleştirildi: id={}, slug={}", id, tenant.slug)
        return tenantRepository.save(tenant).toResponse()
    }

    @Transactional
    fun deactivate(id: String): TenantResponse {
        val tenant = tenantRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Tenant bulunamadı: $id") }

        tenant.isActive = false
        logger.info("Tenant deaktifleştirildi: id={}, slug={}", id, tenant.slug)
        return tenantRepository.save(tenant).toResponse()
    }
}
