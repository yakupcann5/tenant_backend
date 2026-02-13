package com.aesthetic.backend.dto.request

data class UpdatePatientRecordRequest(
    val bloodType: String? = null,
    val allergies: String? = null,
    val chronicConditions: String? = null,
    val currentMedications: String? = null,
    val medicalNotes: String? = null
)
