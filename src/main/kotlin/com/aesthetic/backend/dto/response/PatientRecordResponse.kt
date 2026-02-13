package com.aesthetic.backend.dto.response

import java.time.Instant

data class PatientRecordResponse(
    val id: String,
    val clientId: String,
    val bloodType: String?,
    val allergies: String,
    val chronicConditions: String,
    val currentMedications: String,
    val medicalNotes: String,
    val createdAt: Instant?,
    val updatedAt: Instant?
)
