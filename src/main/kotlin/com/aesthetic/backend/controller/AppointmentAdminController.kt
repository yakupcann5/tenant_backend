package com.aesthetic.backend.controller

import com.aesthetic.backend.domain.appointment.AppointmentStatus
import com.aesthetic.backend.dto.request.CreateAppointmentRequest
import com.aesthetic.backend.dto.request.CreateRecurringAppointmentRequest
import com.aesthetic.backend.dto.request.RescheduleAppointmentRequest
import com.aesthetic.backend.dto.request.UpdateAppointmentStatusRequest
import com.aesthetic.backend.dto.response.*
import com.aesthetic.backend.usecase.AppointmentManagementService
import com.aesthetic.backend.usecase.RecurringAppointmentService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/admin/appointments")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class AppointmentAdminController(
    private val appointmentManagementService: AppointmentManagementService,
    private val recurringAppointmentService: RecurringAppointmentService
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        @RequestParam(required = false) status: AppointmentStatus?,
        @RequestParam(required = false) staffId: String?,
        pageable: Pageable
    ): ResponseEntity<PagedResponse<AppointmentSummaryResponse>> {
        val result = appointmentManagementService.listForAdmin(date, status, staffId, pageable)
        return ResponseEntity.ok(result)
    }

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateAppointmentRequest
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val result = appointmentManagementService.createAppointment(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse(data = result, message = "Randevu oluşturuldu")
        )
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val result = appointmentManagementService.getById(id)
        return ResponseEntity.ok(ApiResponse(data = result))
    }

    @PatchMapping("/{id}/status")
    fun updateStatus(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateAppointmentStatusRequest
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val result = appointmentManagementService.updateStatus(id, request)
        return ResponseEntity.ok(ApiResponse(data = result, message = "Randevu durumu güncellendi"))
    }

    @PostMapping("/{id}/reschedule")
    fun reschedule(
        @PathVariable id: String,
        @Valid @RequestBody request: RescheduleAppointmentRequest
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val result = appointmentManagementService.reschedule(id, request)
        return ResponseEntity.ok(ApiResponse(data = result, message = "Randevu yeniden planlandı"))
    }

    @PostMapping("/recurring")
    fun createRecurring(
        @Valid @RequestBody request: CreateRecurringAppointmentRequest
    ): ResponseEntity<ApiResponse<RecurringAppointmentResponse>> {
        val result = recurringAppointmentService.createRecurring(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse(data = result, message = "Tekrarlayan randevular oluşturuldu")
        )
    }
}
