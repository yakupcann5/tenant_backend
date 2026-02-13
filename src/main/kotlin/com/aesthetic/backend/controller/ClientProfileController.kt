package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.UpdateProfileRequest
import com.aesthetic.backend.dto.response.*
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.usecase.ClientManagementService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/client")
@PreAuthorize("hasAuthority('CLIENT')")
class ClientProfileController(
    private val clientManagementService: ClientManagementService
) {

    @GetMapping("/profile")
    fun getProfile(
        @AuthenticationPrincipal principal: UserPrincipal
    ): ResponseEntity<ApiResponse<UserResponse>> {
        return ResponseEntity.ok(ApiResponse(data = clientManagementService.getClientProfile(principal.id)))
    }

    @PatchMapping("/profile")
    fun updateProfile(
        @AuthenticationPrincipal principal: UserPrincipal,
        @Valid @RequestBody request: UpdateProfileRequest
    ): ResponseEntity<ApiResponse<UserResponse>> {
        return ResponseEntity.ok(
            ApiResponse(data = clientManagementService.updateClientProfile(principal.id, request), message = "Profil güncellendi")
        )
    }

    @GetMapping("/appointments")
    fun getAppointments(
        @AuthenticationPrincipal principal: UserPrincipal,
        pageable: Pageable
    ): ResponseEntity<PagedResponse<AppointmentSummaryResponse>> {
        return ResponseEntity.ok(clientManagementService.getAppointmentHistory(principal.id, pageable))
    }
}
