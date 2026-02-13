package com.aesthetic.backend.controller

import com.aesthetic.backend.domain.appointment.AppointmentStatus
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.AppointmentResponse
import com.aesthetic.backend.dto.response.AppointmentSummaryResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.usecase.AppointmentManagementService
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/client")
@PreAuthorize("hasAuthority('CLIENT')")
class ClientAppointmentController(
    private val appointmentManagementService: AppointmentManagementService
) {

    @GetMapping("/my-appointments")
    fun listMyAppointments(
        @RequestParam(required = false) status: AppointmentStatus?,
        pageable: Pageable,
        @AuthenticationPrincipal principal: UserPrincipal
    ): ResponseEntity<PagedResponse<AppointmentSummaryResponse>> {
        val result = appointmentManagementService.listForClient(principal.id, status, pageable)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/appointments/{id}")
    fun getById(
        @PathVariable id: String
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val result = appointmentManagementService.getById(id)
        return ResponseEntity.ok(ApiResponse(data = result))
    }

    @PostMapping("/appointments/{id}/cancel")
    fun cancel(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UserPrincipal
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val result = appointmentManagementService.cancelByClient(id, principal.id)
        return ResponseEntity.ok(ApiResponse(data = result, message = "Randevu iptal edildi"))
    }
}
