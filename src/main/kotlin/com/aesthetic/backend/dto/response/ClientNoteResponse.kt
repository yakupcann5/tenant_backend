package com.aesthetic.backend.dto.response

import java.time.Instant

data class ClientNoteResponse(
    val id: String,
    val clientId: String,
    val authorId: String?,
    val authorName: String?,
    val content: String,
    val isPrivate: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?
)
