package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class CreateReviewRequest(
    @field:Min(value = 1, message = "Puan en az 1 olmalıdır")
    @field:Max(value = 5, message = "Puan en fazla 5 olmalıdır")
    val rating: Int,

    @field:NotBlank(message = "Yorum boş olamaz")
    val comment: String,

    val appointmentId: String? = null,

    val serviceId: String? = null
)
