package com.aesthetic.backend.dto.request

data class UpdateBlogPostRequest(
    val title: String? = null,
    val summary: String? = null,
    val content: String? = null,
    val coverImage: String? = null,
    val tags: List<String>? = null,
    val seoTitle: String? = null,
    val seoDescription: String? = null,
    val ogImage: String? = null
)
