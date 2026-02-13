package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotBlank

data class CreateBlogPostRequest(
    @field:NotBlank(message = "Başlık boş olamaz")
    val title: String,

    val summary: String = "",

    @field:NotBlank(message = "İçerik boş olamaz")
    val content: String,

    val coverImage: String? = null,
    val tags: List<String> = emptyList(),
    val seoTitle: String? = null,
    val seoDescription: String? = null,
    val ogImage: String? = null
)
