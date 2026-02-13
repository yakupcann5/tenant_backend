package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.review.Review
import com.aesthetic.backend.dto.response.ReviewResponse

fun Review.toResponse(): ReviewResponse = ReviewResponse(
    id = id!!,
    clientName = clientName,
    appointmentId = appointment?.id,
    serviceId = service?.id,
    serviceName = service?.title,
    rating = rating,
    comment = comment,
    approvalStatus = approvalStatus,
    adminResponse = adminResponse,
    adminResponseAt = adminResponseAt,
    createdAt = createdAt
)
