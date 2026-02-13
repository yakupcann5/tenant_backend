package com.aesthetic.backend.dto.response

import java.time.Instant

data class GalleryItemResponse(
    val id: String,
    val title: String,
    val description: String,
    val imageUrl: String,
    val beforeImageUrl: String?,
    val afterImageUrl: String?,
    val isActive: Boolean,
    val sortOrder: Int,
    val category: String,
    val serviceId: String?,
    val serviceName: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?
)
