package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.UpdateSiteSettingsRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.SiteSettingsResponse
import com.aesthetic.backend.usecase.SiteSettingsService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/settings")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class SiteSettingsAdminController(
    private val siteSettingsService: SiteSettingsService
) {

    @GetMapping
    fun getSettings(): ResponseEntity<ApiResponse<SiteSettingsResponse>> {
        val settings = siteSettingsService.getSettings()
        return ResponseEntity.ok(ApiResponse(data = settings))
    }

    @PatchMapping
    fun updateSettings(
        @Valid @RequestBody request: UpdateSiteSettingsRequest
    ): ResponseEntity<ApiResponse<SiteSettingsResponse>> {
        val settings = siteSettingsService.updateSettings(request)
        return ResponseEntity.ok(ApiResponse(data = settings, message = "Ayarlar güncellendi"))
    }
}
