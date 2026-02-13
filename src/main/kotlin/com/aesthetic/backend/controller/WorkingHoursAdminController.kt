package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.SetWorkingHoursRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.WorkingHoursResponse
import com.aesthetic.backend.usecase.WorkingHoursService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/working-hours")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class WorkingHoursAdminController(
    private val workingHoursService: WorkingHoursService
) {

    @GetMapping
    fun getFacilityHours(): ResponseEntity<ApiResponse<List<WorkingHoursResponse>>> {
        val hours = workingHoursService.getFacilityHours()
        return ResponseEntity.ok(ApiResponse(data = hours))
    }

    @PostMapping
    fun setFacilityHours(
        @Valid @RequestBody request: SetWorkingHoursRequest
    ): ResponseEntity<ApiResponse<List<WorkingHoursResponse>>> {
        val hours = workingHoursService.setFacilityHours(request)
        return ResponseEntity.ok(ApiResponse(data = hours, message = "Çalışma saatleri güncellendi"))
    }

    @GetMapping("/staff/{staffId}")
    fun getStaffHours(
        @PathVariable staffId: String
    ): ResponseEntity<ApiResponse<List<WorkingHoursResponse>>> {
        val hours = workingHoursService.getStaffHours(staffId)
        return ResponseEntity.ok(ApiResponse(data = hours))
    }

    @PostMapping("/staff/{staffId}")
    fun setStaffHours(
        @PathVariable staffId: String,
        @Valid @RequestBody request: SetWorkingHoursRequest
    ): ResponseEntity<ApiResponse<List<WorkingHoursResponse>>> {
        val hours = workingHoursService.setStaffHours(staffId, request)
        return ResponseEntity.ok(ApiResponse(data = hours, message = "Personel çalışma saatleri güncellendi"))
    }
}
