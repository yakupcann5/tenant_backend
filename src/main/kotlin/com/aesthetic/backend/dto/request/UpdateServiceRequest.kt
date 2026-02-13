package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal

data class UpdateServiceRequest(
    val title: String? = null,
    val slug: String? = null,
    val categoryId: String? = null,
    val removeCategoryId: Boolean = false,
    val shortDescription: String? = null,
    val description: String? = null,

    @field:PositiveOrZero(message = "Fiyat sıfır veya pozitif olmalıdır")
    val price: BigDecimal? = null,

    val currency: String? = null,

    @field:Min(value = 5, message = "Süre en az 5 dakika olmalıdır")
    val durationMinutes: Int? = null,

    @field:PositiveOrZero(message = "Tampon süresi sıfır veya pozitif olmalıdır")
    val bufferMinutes: Int? = null,

    val image: String? = null,
    val benefits: List<String>? = null,
    val processSteps: List<String>? = null,
    val recovery: String? = null,
    val isActive: Boolean? = null,
    val sortOrder: Int? = null,
    val metaTitle: String? = null,
    val metaDescription: String? = null,
    val ogImage: String? = null
)
