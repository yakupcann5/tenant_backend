package com.aesthetic.backend.dto.request

data class CreatePatientRecordRequest(
    val bloodType: String? = null,
    val allergies: String = "",
    val chronicConditions: String = "",
    val currentMedications: String = "",
    val medicalNotes: String = ""
)
