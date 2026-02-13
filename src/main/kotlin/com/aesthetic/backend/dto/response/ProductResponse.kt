package com.aesthetic.backend.dto.response

import java.math.BigDecimal
import java.time.Instant

data class ProductResponse(
    val id: String,
    val slug: String,
    val title: String,
    val shortDescription: String,
    val description: String,
    val price: BigDecimal,
    val currency: String,
    val image: String?,
    val stockQuantity: Int?,
    val isActive: Boolean,
    val sortOrder: Int,
    val features: List<String>,
    val seoTitle: String?,
    val seoDescription: String?,
    val ogImage: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?
)
