package com.aesthetic.backend.dto.response

import java.math.BigDecimal
import java.time.Instant

data class ServiceResponse(
    val id: String,
    val slug: String,
    val title: String,
    val categoryId: String?,
    val categoryName: String?,
    val shortDescription: String,
    val description: String,
    val price: BigDecimal,
    val currency: String,
    val durationMinutes: Int,
    val bufferMinutes: Int,
    val image: String?,
    val benefits: List<String>,
    val processSteps: List<String>,
    val recovery: String?,
    val isActive: Boolean,
    val sortOrder: Int,
    val metaTitle: String?,
    val metaDescription: String?,
    val ogImage: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?
)
