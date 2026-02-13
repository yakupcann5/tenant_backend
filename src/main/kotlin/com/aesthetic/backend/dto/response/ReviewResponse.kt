package com.aesthetic.backend.dto.response

import com.aesthetic.backend.domain.review.ReviewApprovalStatus
import java.time.Instant

data class ReviewResponse(
    val id: String,
    val clientName: String,
    val appointmentId: String?,
    val serviceId: String?,
    val serviceName: String?,
    val rating: Int,
    val comment: String,
    val approvalStatus: ReviewApprovalStatus,
    val adminResponse: String?,
    val adminResponseAt: Instant?,
    val createdAt: Instant?
)
