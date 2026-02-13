package com.aesthetic.backend.dto.request

import com.aesthetic.backend.security.Password
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class RegisterRequest(
    @field:NotBlank(message = "Ad boş olamaz")
    val firstName: String,

    val lastName: String = "",

    @field:NotBlank(message = "E-posta adresi boş olamaz")
    @field:Email(message = "Geçerli bir e-posta adresi giriniz")
    val email: String,

    @field:Password
    val password: String,

    val phone: String = ""
)
