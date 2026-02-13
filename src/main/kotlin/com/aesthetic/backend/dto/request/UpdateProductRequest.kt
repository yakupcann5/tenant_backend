package com.aesthetic.backend.dto.request

import java.math.BigDecimal

data class UpdateProductRequest(
    val title: String? = null,
    val shortDescription: String? = null,
    val description: String? = null,
    val price: BigDecimal? = null,
    val currency: String? = null,
    val image: String? = null,
    val stockQuantity: Int? = null,
    val isActive: Boolean? = null,
    val sortOrder: Int? = null,
    val features: List<String>? = null,
    val seoTitle: String? = null,
    val seoDescription: String? = null,
    val ogImage: String? = null
)
