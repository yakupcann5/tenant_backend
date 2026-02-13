package com.aesthetic.backend.dto.request

data class UpdateClientNoteRequest(
    val content: String? = null,
    val isPrivate: Boolean? = null
)
