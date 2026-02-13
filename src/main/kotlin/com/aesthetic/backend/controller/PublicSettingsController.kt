package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.SiteSettingsResponse
import com.aesthetic.backend.usecase.SiteSettingsService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/public/settings")
class PublicSettingsController(
    private val siteSettingsService: SiteSettingsService
) {

    @GetMapping
    fun getSettings(): ResponseEntity<ApiResponse<SiteSettingsResponse>> {
        val settings = siteSettingsService.getSettings()
        return ResponseEntity.ok(ApiResponse(data = settings))
    }
}
