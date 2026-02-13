package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotBlank

data class AdminRespondReviewRequest(
    @field:NotBlank(message = "Yanıt boş olamaz")
    val response: String
)
