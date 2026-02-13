package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.patient.PatientRecord
import com.aesthetic.backend.domain.patient.TreatmentHistory
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.*
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.PatientRecordResponse
import com.aesthetic.backend.dto.response.TreatmentHistoryResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.PatientRecordRepository
import com.aesthetic.backend.repository.TreatmentHistoryRepository
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PatientRecordService(
    private val patientRecordRepository: PatientRecordRepository,
    private val treatmentHistoryRepository: TreatmentHistoryRepository,
    private val entityManager: EntityManager
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun getOrCreateRecord(clientId: String): PatientRecordResponse {
        val tenantId = TenantContext.getTenantId()
        val existing = patientRecordRepository.findByClientIdAndTenantId(clientId, tenantId)
        if (existing != null) return existing.toResponse()

        val record = PatientRecord(
            client = entityManager.getReference(User::class.java, clientId)
        )
        return patientRecordRepository.save(record).toResponse()
    }

    @Transactional
    fun updateRecord(clientId: String, request: UpdatePatientRecordRequest): PatientRecordResponse {
        val tenantId = TenantContext.getTenantId()
        val record = patientRecordRepository.findByClientIdAndTenantId(clientId, tenantId)
            ?: throw ResourceNotFoundException("Hasta kaydı bulunamadı")

        request.bloodType?.let { record.bloodType = it }
        request.allergies?.let { record.allergies = it }
        request.chronicConditions?.let { record.chronicConditions = it }
        request.currentMedications?.let { record.currentMedications = it }
        request.medicalNotes?.let { record.medicalNotes = it }

        return patientRecordRepository.save(record).toResponse()
    }

    @Transactional
    fun addTreatment(clientId: String, request: CreateTreatmentRequest, principal: UserPrincipal): TreatmentHistoryResponse {
        val treatment = TreatmentHistory(
            client = entityManager.getReference(User::class.java, clientId),
            performedBy = entityManager.getReference(User::class.java, principal.id),
            treatmentDate = request.treatmentDate,
            title = request.title,
            description = request.description,
            notes = request.notes,
            beforeImage = request.beforeImage,
            afterImage = request.afterImage
        )
        val saved = treatmentHistoryRepository.save(treatment)
        return treatmentHistoryRepository.findByIdWithPerformedBy(saved.id!!)!!.toResponse()
    }

    @Transactional
    fun updateTreatment(id: String, request: UpdateTreatmentRequest): TreatmentHistoryResponse {
        val treatment = treatmentHistoryRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Tedavi kaydı bulunamadı: $id") }

        request.treatmentDate?.let { treatment.treatmentDate = it }
        request.title?.let { treatment.title = it }
        request.description?.let { treatment.description = it }
        request.notes?.let { treatment.notes = it }
        request.beforeImage?.let { treatment.beforeImage = it }
        request.afterImage?.let { treatment.afterImage = it }

        treatmentHistoryRepository.save(treatment)
        return treatmentHistoryRepository.findByIdWithPerformedBy(id)!!.toResponse()
    }

    @Transactional
    fun deleteTreatment(id: String) {
        val treatment = treatmentHistoryRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Tedavi kaydı bulunamadı: $id") }
        treatmentHistoryRepository.delete(treatment)
    }

    @Transactional(readOnly = true)
    fun listTreatments(clientId: String, pageable: Pageable): PagedResponse<TreatmentHistoryResponse> {
        val tenantId = TenantContext.getTenantId()
        return treatmentHistoryRepository.findAllByClientIdAndTenantId(clientId, tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }
}
