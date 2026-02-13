package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.PlatformStatsResponse
import com.aesthetic.backend.usecase.PlatformStatsService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/platform/stats")
@PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
class PlatformStatsController(
    private val platformStatsService: PlatformStatsService
) {

    @GetMapping
    fun getStats(): ResponseEntity<ApiResponse<PlatformStatsResponse>> {
        return ResponseEntity.ok(ApiResponse(data = platformStatsService.getStats()))
    }
}
