package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.review.Review
import com.aesthetic.backend.domain.review.ReviewApprovalStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ReviewRepository : JpaRepository<Review, String> {
    fun findAllByTenantId(tenantId: String, pageable: Pageable): Page<Review>
    fun findAllByTenantIdAndApprovalStatus(tenantId: String, approvalStatus: ReviewApprovalStatus, pageable: Pageable): Page<Review>

    fun countByTenantId(tenantId: String): Long

    @Query("SELECT COALESCE(AVG(r.rating), 0) FROM Review r WHERE r.tenantId = :tenantId")
    fun averageRatingByTenantId(@Param("tenantId") tenantId: String): Double?

    @Modifying
    @Query("UPDATE Review r SET r.clientName = '[Silindi]', r.comment = '[Silindi]' WHERE r.client.id = :userId")
    fun anonymizeByUserId(@Param("userId") userId: String)
}
