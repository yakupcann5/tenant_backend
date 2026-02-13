package com.aesthetic.backend.dto.request

import com.aesthetic.backend.security.Password
import jakarta.validation.constraints.NotBlank

data class ResetPasswordRequest(
    @field:NotBlank(message = "Token boş olamaz")
    val token: String,

    @field:Password
    val newPassword: String
)
