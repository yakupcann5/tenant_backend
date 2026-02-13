package com.aesthetic.backend.controller

import com.aesthetic.backend.config.RequiresModule
import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.dto.request.CreateTreatmentRequest
import com.aesthetic.backend.dto.request.UpdatePatientRecordRequest
import com.aesthetic.backend.dto.request.UpdateTreatmentRequest
import com.aesthetic.backend.dto.response.*
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.usecase.PatientRecordService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/patients/{clientId}/records")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
@RequiresModule(FeatureModule.PATIENT_RECORDS)
class PatientRecordAdminController(
    private val patientRecordService: PatientRecordService
) {

    @GetMapping
    fun getRecord(@PathVariable clientId: String): ResponseEntity<ApiResponse<PatientRecordResponse>> {
        return ResponseEntity.ok(ApiResponse(data = patientRecordService.getOrCreateRecord(clientId)))
    }

    @PatchMapping
    fun updateRecord(
        @PathVariable clientId: String,
        @Valid @RequestBody request: UpdatePatientRecordRequest
    ): ResponseEntity<ApiResponse<PatientRecordResponse>> {
        return ResponseEntity.ok(ApiResponse(data = patientRecordService.updateRecord(clientId, request), message = "Hasta kaydı güncellendi"))
    }

    @PostMapping("/treatments")
    fun addTreatment(
        @PathVariable clientId: String,
        @Valid @RequestBody request: CreateTreatmentRequest,
        @AuthenticationPrincipal principal: UserPrincipal
    ): ResponseEntity<ApiResponse<TreatmentHistoryResponse>> {
        val result = patientRecordService.addTreatment(clientId, request, principal)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(data = result, message = "Tedavi kaydı oluşturuldu"))
    }

    @PatchMapping("/treatments/{id}")
    fun updateTreatment(
        @PathVariable clientId: String,
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateTreatmentRequest
    ): ResponseEntity<ApiResponse<TreatmentHistoryResponse>> {
        return ResponseEntity.ok(ApiResponse(data = patientRecordService.updateTreatment(id, request), message = "Tedavi kaydı güncellendi"))
    }

    @DeleteMapping("/treatments/{id}")
    fun deleteTreatment(@PathVariable clientId: String, @PathVariable id: String): ResponseEntity<Void> {
        patientRecordService.deleteTreatment(id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/treatments")
    fun listTreatments(@PathVariable clientId: String, pageable: Pageable): ResponseEntity<PagedResponse<TreatmentHistoryResponse>> {
        return ResponseEntity.ok(patientRecordService.listTreatments(clientId, pageable))
    }
}
