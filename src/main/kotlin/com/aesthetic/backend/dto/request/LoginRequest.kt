package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotBlank

data class LoginRequest(
    @field:NotBlank(message = "E-posta adresi boş olamaz")
    val email: String,

    @field:NotBlank(message = "Şifre boş olamaz")
    val password: String
)
