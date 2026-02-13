package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.gallery.GalleryItem
import com.aesthetic.backend.dto.response.GalleryItemResponse

fun GalleryItem.toResponse(): GalleryItemResponse = GalleryItemResponse(
    id = id!!,
    title = title,
    description = description,
    imageUrl = imageUrl,
    beforeImageUrl = beforeImageUrl,
    afterImageUrl = afterImageUrl,
    isActive = isActive,
    sortOrder = sortOrder,
    category = category,
    serviceId = service?.id,
    serviceName = service?.title,
    createdAt = createdAt,
    updatedAt = updatedAt
)
