package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class ForgotPasswordRequest(
    @field:NotBlank(message = "E-posta adresi boş olamaz")
    @field:Email(message = "Geçerli bir e-posta adresi giriniz")
    val email: String
)
