package com.aesthetic.backend.dto.response

data class SeoResponse(
    val entityType: String,
    val entityId: String,
    val title: String,
    val slug: String,
    val seoTitle: String?,
    val seoDescription: String?,
    val ogImage: String?
)
