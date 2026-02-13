package com.aesthetic.backend.dto.response

import java.time.Instant
import java.time.LocalDate

data class TreatmentHistoryResponse(
    val id: String,
    val clientId: String,
    val performedByName: String?,
    val treatmentDate: LocalDate,
    val title: String,
    val description: String,
    val notes: String,
    val beforeImage: String?,
    val afterImage: String?,
    val createdAt: Instant?
)
