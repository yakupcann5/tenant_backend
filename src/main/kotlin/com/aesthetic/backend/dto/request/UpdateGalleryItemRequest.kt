package com.aesthetic.backend.dto.request

data class UpdateGalleryItemRequest(
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val beforeImageUrl: String? = null,
    val afterImageUrl: String? = null,
    val isActive: Boolean? = null,
    val sortOrder: Int? = null,
    val category: String? = null,
    val serviceId: String? = null,
    val removeService: Boolean = false
)
