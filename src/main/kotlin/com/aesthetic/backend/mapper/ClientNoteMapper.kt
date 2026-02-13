package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.note.ClientNote
import com.aesthetic.backend.dto.response.ClientNoteResponse

fun ClientNote.toResponse(): ClientNoteResponse = ClientNoteResponse(
    id = id!!,
    clientId = client.id!!,
    authorId = author?.id,
    authorName = author?.let { "${it.firstName} ${it.lastName}".trim() },
    content = content,
    isPrivate = isPrivate,
    createdAt = createdAt,
    updatedAt = updatedAt
)
