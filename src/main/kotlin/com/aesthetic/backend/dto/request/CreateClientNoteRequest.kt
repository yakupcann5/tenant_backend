package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotBlank

data class CreateClientNoteRequest(
    @field:NotBlank(message = "Müşteri ID boş olamaz")
    val clientId: String,

    @field:NotBlank(message = "Not içeriği boş olamaz")
    val content: String,

    val isPrivate: Boolean = false
)
