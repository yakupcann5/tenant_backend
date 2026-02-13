package com.aesthetic.backend.dto.response

import java.time.Instant

data class ServiceCategoryResponse(
    val id: String,
    val slug: String,
    val name: String,
    val description: String?,
    val image: String?,
    val sortOrder: Int,
    val isActive: Boolean,
    val serviceCount: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?
)
