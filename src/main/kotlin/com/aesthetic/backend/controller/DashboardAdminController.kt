package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.DashboardStatsResponse
import com.aesthetic.backend.usecase.DashboardService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class DashboardAdminController(
    private val dashboardService: DashboardService
) {

    @GetMapping("/stats")
    fun getTodayStats(): ResponseEntity<ApiResponse<DashboardStatsResponse>> {
        val result = dashboardService.getTodayStats()
        return ResponseEntity.ok(ApiResponse(data = result))
    }
}
