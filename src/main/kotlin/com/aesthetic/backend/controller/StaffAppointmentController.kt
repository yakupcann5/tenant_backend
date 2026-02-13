package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.AppointmentResponse
import com.aesthetic.backend.dto.response.AppointmentSummaryResponse
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.usecase.AppointmentManagementService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/staff")
@PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'STAFF')")
class StaffAppointmentController(
    private val appointmentManagementService: AppointmentManagementService
) {

    @GetMapping("/my-calendar")
    fun getMyCalendar(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @AuthenticationPrincipal principal: UserPrincipal
    ): ResponseEntity<ApiResponse<List<AppointmentSummaryResponse>>> {
        val result = appointmentManagementService.getStaffCalendar(principal.id, date)
        return ResponseEntity.ok(ApiResponse(data = result))
    }

    @GetMapping("/appointments/{id}")
    fun getById(
        @PathVariable id: String
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val result = appointmentManagementService.getById(id)
        return ResponseEntity.ok(ApiResponse(data = result))
    }
}
