package com.aesthetic.backend.dto.request

data class UpdateServiceCategoryRequest(
    val name: String? = null,
    val slug: String? = null,
    val description: String? = null,
    val image: String? = null,
    val isActive: Boolean? = null,
    val sortOrder: Int? = null
)
