package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotBlank

data class CreateServiceCategoryRequest(
    @field:NotBlank(message = "Kategori adı boş olamaz")
    val name: String,

    @field:NotBlank(message = "Slug boş olamaz")
    val slug: String,

    val description: String? = null,

    val image: String? = null
)
