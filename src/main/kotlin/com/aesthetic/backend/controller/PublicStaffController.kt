package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.PublicStaffResponse
import com.aesthetic.backend.usecase.StaffManagementService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/public/staff")
class PublicStaffController(
    private val staffManagementService: StaffManagementService
) {

    @GetMapping
    fun listStaff(): ResponseEntity<ApiResponse<List<PublicStaffResponse>>> {
        return ResponseEntity.ok(ApiResponse(data = staffManagementService.listPublicStaff()))
    }
}
