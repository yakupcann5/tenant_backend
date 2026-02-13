package com.aesthetic.backend.dto.request

data class UpdateSeoRequest(
    val seoTitle: String? = null,
    val seoDescription: String? = null,
    val ogImage: String? = null
)
