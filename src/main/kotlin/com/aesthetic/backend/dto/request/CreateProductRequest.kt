package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal

data class CreateProductRequest(
    @field:NotBlank(message = "Başlık boş olamaz")
    val title: String,

    val shortDescription: String = "",

    @field:NotBlank(message = "Açıklama boş olamaz")
    val description: String,

    @field:PositiveOrZero(message = "Fiyat negatif olamaz")
    val price: BigDecimal = BigDecimal.ZERO,

    val currency: String = "TRY",
    val image: String? = null,
    val stockQuantity: Int? = null,
    val sortOrder: Int = 0,
    val features: List<String> = emptyList(),
    val seoTitle: String? = null,
    val seoDescription: String? = null,
    val ogImage: String? = null
)
