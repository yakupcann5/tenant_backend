package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDate

data class CreateTreatmentRequest(
    @field:NotNull(message = "Tedavi tarihi zorunludur")
    val treatmentDate: LocalDate,

    @field:NotBlank(message = "Başlık boş olamaz")
    val title: String,

    val description: String = "",
    val notes: String = "",
    val beforeImage: String? = null,
    val afterImage: String? = null
)
