package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.UpdateSeoRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.SeoResponse
import com.aesthetic.backend.usecase.SeoService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/seo")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class SeoAdminController(
    private val seoService: SeoService
) {

    @GetMapping("/pages")
    fun listPages(): ResponseEntity<ApiResponse<List<SeoResponse>>> {
        return ResponseEntity.ok(ApiResponse(data = seoService.listSeoPages()))
    }

    @PatchMapping("/{entityType}/{entityId}")
    fun updateSeo(
        @PathVariable entityType: String,
        @PathVariable entityId: String,
        @Valid @RequestBody request: UpdateSeoRequest
    ): ResponseEntity<ApiResponse<SeoResponse>> {
        return ResponseEntity.ok(ApiResponse(data = seoService.updateSeo(entityType, entityId, request)))
    }
}
