package com.aesthetic.backend.dto.response

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val tokenType: String = "Bearer",
    val user: UserResponse
)
