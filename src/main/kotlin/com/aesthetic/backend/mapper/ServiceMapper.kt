package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.service.Service
import com.aesthetic.backend.dto.response.ServiceResponse

fun Service.toResponse(): ServiceResponse = ServiceResponse(
    id = id!!,
    slug = slug,
    title = title,
    categoryId = category?.id,
    categoryName = category?.name,
    shortDescription = shortDescription,
    description = description,
    price = price,
    currency = currency,
    durationMinutes = durationMinutes,
    bufferMinutes = bufferMinutes,
    image = image,
    benefits = benefits.toList(),
    processSteps = processSteps.toList(),
    recovery = recovery,
    isActive = isActive,
    sortOrder = sortOrder,
    metaTitle = metaTitle,
    metaDescription = metaDescription,
    ogImage = ogImage,
    createdAt = createdAt,
    updatedAt = updatedAt
)
