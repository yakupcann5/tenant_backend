package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateStaffRequest(
    @field:NotBlank(message = "Ad boş olamaz")
    @field:Size(max = 100)
    val firstName: String,

    @field:Size(max = 100)
    val lastName: String = "",

    @field:NotBlank(message = "E-posta boş olamaz")
    @field:Email(message = "Geçerli bir e-posta adresi giriniz")
    val email: String,

    @field:NotBlank(message = "Şifre boş olamaz")
    @field:Size(min = 8, message = "Şifre en az 8 karakter olmalıdır")
    val password: String,

    val phone: String = "",

    @field:Size(max = 100)
    val title: String? = null
)
