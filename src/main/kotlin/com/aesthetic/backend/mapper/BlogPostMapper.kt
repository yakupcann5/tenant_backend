package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.blog.BlogPost
import com.aesthetic.backend.dto.response.BlogPostResponse

fun BlogPost.toResponse(): BlogPostResponse {
    val wordCount = content.replace(Regex("<[^>]*>"), " ").trim().split(Regex("\\s+")).size
    val minutes = maxOf(1, wordCount / 200)
    return BlogPostResponse(
        id = id!!,
        slug = slug,
        title = title,
        summary = summary,
        content = content,
        coverImage = coverImage,
        isPublished = isPublished,
        publishedAt = publishedAt,
        tags = tags.toList(),
        seoTitle = seoTitle,
        seoDescription = seoDescription,
        ogImage = ogImage,
        readTime = "$minutes dk okuma",
        authorName = authorName,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
