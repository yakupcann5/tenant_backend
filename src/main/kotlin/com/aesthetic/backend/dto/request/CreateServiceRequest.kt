package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Min
import java.math.BigDecimal

data class CreateServiceRequest(
    @field:NotBlank(message = "Başlık boş olamaz")
    val title: String,

    @field:NotBlank(message = "Slug boş olamaz")
    val slug: String,

    val categoryId: String? = null,

    val shortDescription: String = "",

    val description: String = "",

    @field:PositiveOrZero(message = "Fiyat sıfır veya pozitif olmalıdır")
    val price: BigDecimal = BigDecimal.ZERO,

    val currency: String = "TRY",

    @field:Min(value = 5, message = "Süre en az 5 dakika olmalıdır")
    val durationMinutes: Int = 30,

    @field:PositiveOrZero(message = "Tampon süresi sıfır veya pozitif olmalıdır")
    val bufferMinutes: Int = 0,

    val image: String? = null,

    val benefits: List<String> = emptyList(),

    val processSteps: List<String> = emptyList(),

    val recovery: String? = null,

    val metaTitle: String? = null,

    val metaDescription: String? = null,

    val ogImage: String? = null
)
