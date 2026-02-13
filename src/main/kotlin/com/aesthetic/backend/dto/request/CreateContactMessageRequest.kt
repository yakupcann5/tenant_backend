package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class CreateContactMessageRequest(
    @field:NotBlank(message = "Ad boş olamaz")
    val name: String,

    @field:NotBlank(message = "E-posta adresi boş olamaz")
    @field:Email(message = "Geçerli bir e-posta adresi giriniz")
    val email: String,

    val phone: String = "",

    val subject: String = "",

    @field:NotBlank(message = "Mesaj boş olamaz")
    val message: String
)
