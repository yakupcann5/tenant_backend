package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.CreateAppointmentRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.AppointmentResponse
import com.aesthetic.backend.dto.response.AvailabilityResponse
import com.aesthetic.backend.usecase.AppointmentManagementService
import com.aesthetic.backend.usecase.AvailabilityService
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/public")
class PublicAppointmentController(
    private val availabilityService: AvailabilityService,
    private val appointmentManagementService: AppointmentManagementService
) {

    @GetMapping("/availability")
    fun getAvailableSlots(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam serviceIds: List<String>,
        @RequestParam(required = false) staffId: String?
    ): ResponseEntity<ApiResponse<List<AvailabilityResponse>>> {
        val result = availabilityService.getAvailableSlots(date, serviceIds, staffId)
        return ResponseEntity.ok(ApiResponse(data = result))
    }

    @PostMapping("/appointments")
    fun createAppointment(
        @Valid @RequestBody request: CreateAppointmentRequest
    ): ResponseEntity<ApiResponse<AppointmentResponse>> {
        val result = appointmentManagementService.createAppointment(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse(data = result, message = "Randevu oluşturuldu")
        )
    }
}
