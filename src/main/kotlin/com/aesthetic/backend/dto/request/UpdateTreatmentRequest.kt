package com.aesthetic.backend.dto.request

import java.time.LocalDate

data class UpdateTreatmentRequest(
    val treatmentDate: LocalDate? = null,
    val title: String? = null,
    val description: String? = null,
    val notes: String? = null,
    val beforeImage: String? = null,
    val afterImage: String? = null
)
