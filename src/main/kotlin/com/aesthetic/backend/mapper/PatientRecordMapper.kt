package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.patient.PatientRecord
import com.aesthetic.backend.domain.patient.TreatmentHistory
import com.aesthetic.backend.dto.response.PatientRecordResponse
import com.aesthetic.backend.dto.response.TreatmentHistoryResponse

fun PatientRecord.toResponse(): PatientRecordResponse = PatientRecordResponse(
    id = id!!,
    clientId = client.id!!,
    bloodType = bloodType,
    allergies = allergies,
    chronicConditions = chronicConditions,
    currentMedications = currentMedications,
    medicalNotes = medicalNotes,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun TreatmentHistory.toResponse(): TreatmentHistoryResponse = TreatmentHistoryResponse(
    id = id!!,
    clientId = client.id!!,
    performedByName = performedBy?.let { "${it.firstName} ${it.lastName}".trim() },
    treatmentDate = treatmentDate,
    title = title,
    description = description,
    notes = notes,
    beforeImage = beforeImage,
    afterImage = afterImage,
    createdAt = createdAt
)
