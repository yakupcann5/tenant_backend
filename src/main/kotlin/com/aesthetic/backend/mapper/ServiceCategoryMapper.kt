package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.service.ServiceCategory
import com.aesthetic.backend.dto.response.ServiceCategoryResponse

fun ServiceCategory.toResponse(serviceCount: Long = 0): ServiceCategoryResponse = ServiceCategoryResponse(
    id = id!!,
    slug = slug,
    name = name,
    description = description,
    image = image,
    sortOrder = sortOrder,
    isActive = isActive,
    serviceCount = serviceCount,
    createdAt = createdAt,
    updatedAt = updatedAt
)
