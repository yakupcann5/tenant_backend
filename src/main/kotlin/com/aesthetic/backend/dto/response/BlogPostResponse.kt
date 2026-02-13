package com.aesthetic.backend.dto.response

import java.time.Instant

data class BlogPostResponse(
    val id: String,
    val slug: String,
    val title: String,
    val summary: String,
    val content: String,
    val coverImage: String?,
    val isPublished: Boolean,
    val publishedAt: Instant?,
    val tags: List<String>,
    val seoTitle: String?,
    val seoDescription: String?,
    val ogImage: String?,
    val readTime: String,
    val authorName: String,
    val createdAt: Instant?,
    val updatedAt: Instant?
)
