package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.CreateStaffRequest
import com.aesthetic.backend.dto.request.UpdateStaffRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.StaffResponse
import com.aesthetic.backend.usecase.StaffManagementService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/staff")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class StaffAdminController(
    private val staffManagementService: StaffManagementService
) {

    @PostMapping
    fun createStaff(
        @Valid @RequestBody request: CreateStaffRequest
    ): ResponseEntity<ApiResponse<StaffResponse>> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(data = staffManagementService.createStaff(request), message = "Personel oluşturuldu"))
    }

    @GetMapping
    fun listStaff(): ResponseEntity<ApiResponse<List<StaffResponse>>> {
        return ResponseEntity.ok(ApiResponse(data = staffManagementService.listStaff()))
    }

    @GetMapping("/{id}")
    fun getStaff(@PathVariable id: String): ResponseEntity<ApiResponse<StaffResponse>> {
        return ResponseEntity.ok(ApiResponse(data = staffManagementService.getStaff(id)))
    }

    @PatchMapping("/{id}")
    fun updateStaff(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateStaffRequest
    ): ResponseEntity<ApiResponse<StaffResponse>> {
        return ResponseEntity.ok(
            ApiResponse(data = staffManagementService.updateStaff(id, request), message = "Personel güncellendi")
        )
    }

    @PatchMapping("/{id}/deactivate")
    fun deactivateStaff(@PathVariable id: String): ResponseEntity<ApiResponse<StaffResponse>> {
        return ResponseEntity.ok(
            ApiResponse(data = staffManagementService.deactivateStaff(id), message = "Personel deaktif edildi")
        )
    }

    @PatchMapping("/{id}/activate")
    fun activateStaff(@PathVariable id: String): ResponseEntity<ApiResponse<StaffResponse>> {
        return ResponseEntity.ok(
            ApiResponse(data = staffManagementService.activateStaff(id), message = "Personel aktif edildi")
        )
    }
}
