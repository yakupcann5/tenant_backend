package com.aesthetic.backend.dto.response

import org.springframework.data.domain.Page
import java.time.Instant

data class PagedResponse<T>(
    val success: Boolean = true,
    val data: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val timestamp: Instant = Instant.now()
)

fun <T, R> Page<T>.toPagedResponse(mapper: (T) -> R): PagedResponse<R> = PagedResponse(
    data = content.map(mapper),
    page = number,
    size = size,
    totalElements = totalElements,
    totalPages = totalPages
)
