package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotBlank

data class RefreshTokenRequest(
    @field:NotBlank(message = "Refresh token boş olamaz")
    val refreshToken: String
)
