package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.UpdateClientStatusRequest
import com.aesthetic.backend.dto.response.*
import com.aesthetic.backend.usecase.ClientManagementService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/patients")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class ClientManagementAdminController(
    private val clientManagementService: ClientManagementService
) {

    @GetMapping
    fun listClients(pageable: Pageable): ResponseEntity<PagedResponse<ClientListResponse>> {
        return ResponseEntity.ok(clientManagementService.listClients(pageable))
    }

    @GetMapping("/{id}")
    fun getClientDetail(@PathVariable id: String): ResponseEntity<ApiResponse<ClientDetailResponse>> {
        return ResponseEntity.ok(ApiResponse(data = clientManagementService.getClientDetail(id)))
    }

    @PatchMapping("/{id}/status")
    fun updateClientStatus(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateClientStatusRequest
    ): ResponseEntity<ApiResponse<ClientDetailResponse>> {
        return ResponseEntity.ok(
            ApiResponse(data = clientManagementService.updateClientStatus(id, request), message = "Müşteri durumu güncellendi")
        )
    }

    @PostMapping("/{id}/unblacklist")
    fun removeFromBlacklist(@PathVariable id: String): ResponseEntity<ApiResponse<ClientDetailResponse>> {
        return ResponseEntity.ok(
            ApiResponse(data = clientManagementService.removeFromBlacklist(id), message = "Müşteri kara listeden çıkarıldı")
        )
    }

    @GetMapping("/{id}/appointments")
    fun getAppointmentHistory(
        @PathVariable id: String,
        pageable: Pageable
    ): ResponseEntity<PagedResponse<AppointmentSummaryResponse>> {
        return ResponseEntity.ok(clientManagementService.getAppointmentHistory(id, pageable))
    }
}
