package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotBlank

data class CreateGalleryItemRequest(
    val title: String = "",
    val description: String = "",

    @field:NotBlank(message = "Resim URL'si boş olamaz")
    val imageUrl: String,

    val beforeImageUrl: String? = null,
    val afterImageUrl: String? = null,
    val sortOrder: Int = 0,
    val category: String = "",
    val serviceId: String? = null
)
