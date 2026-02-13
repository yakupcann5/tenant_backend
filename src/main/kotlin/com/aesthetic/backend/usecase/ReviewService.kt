package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.appointment.Appointment
import com.aesthetic.backend.domain.review.Review
import com.aesthetic.backend.domain.review.ReviewApprovalStatus
import com.aesthetic.backend.dto.request.AdminRespondReviewRequest
import com.aesthetic.backend.dto.request.CreateReviewRequest
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.ReviewResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.ReviewRepository
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import jakarta.persistence.EntityManager
import java.time.Instant

@Service
class ReviewService(
    private val reviewRepository: ReviewRepository,
    private val userRepository: UserRepository,
    private val entityManager: EntityManager
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun createByClient(request: CreateReviewRequest, principal: UserPrincipal): ReviewResponse {
        val client = userRepository.findById(principal.id)
            .orElseThrow { ResourceNotFoundException("Kullanıcı bulunamadı") }

        val review = Review(
            rating = request.rating,
            comment = request.comment,
            clientName = "${client.firstName} ${client.lastName}".trim(),
            client = client,
            appointment = request.appointmentId?.let {
                entityManager.getReference(Appointment::class.java, it)
            },
            service = request.serviceId?.let {
                entityManager.getReference(com.aesthetic.backend.domain.service.Service::class.java, it)
            }
        )
        return reviewRepository.save(review).toResponse()
    }

    @Transactional(readOnly = true)
    fun listAll(pageable: Pageable): PagedResponse<ReviewResponse> {
        val tenantId = TenantContext.getTenantId()
        return reviewRepository.findAllByTenantId(tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun listApproved(pageable: Pageable): PagedResponse<ReviewResponse> {
        val tenantId = TenantContext.getTenantId()
        return reviewRepository.findAllByTenantIdAndApprovalStatus(tenantId, ReviewApprovalStatus.APPROVED, pageable)
            .toPagedResponse { it.toResponse() }
    }

    @Transactional
    fun approve(id: String): ReviewResponse {
        val review = reviewRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Değerlendirme bulunamadı: $id") }
        review.approvalStatus = ReviewApprovalStatus.APPROVED
        return reviewRepository.save(review).toResponse()
    }

    @Transactional
    fun reject(id: String): ReviewResponse {
        val review = reviewRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Değerlendirme bulunamadı: $id") }
        review.approvalStatus = ReviewApprovalStatus.REJECTED
        return reviewRepository.save(review).toResponse()
    }

    @Transactional
    fun addAdminResponse(id: String, request: AdminRespondReviewRequest): ReviewResponse {
        val review = reviewRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Değerlendirme bulunamadı: $id") }
        review.adminResponse = request.response
        review.adminResponseAt = Instant.now()
        return reviewRepository.save(review).toResponse()
    }

    @Transactional
    fun delete(id: String) {
        val review = reviewRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Değerlendirme bulunamadı: $id") }
        reviewRepository.delete(review)
    }
}
