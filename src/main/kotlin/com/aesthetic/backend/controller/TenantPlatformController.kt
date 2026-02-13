package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.TenantDetailResponse
import com.aesthetic.backend.dto.response.TenantResponse
import com.aesthetic.backend.usecase.TenantPlatformService
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/platform/tenants")
@PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
class TenantPlatformController(
    private val tenantPlatformService: TenantPlatformService
) {

    @GetMapping
    fun list(pageable: Pageable): ResponseEntity<PagedResponse<TenantResponse>> {
        return ResponseEntity.ok(tenantPlatformService.listAll(pageable))
    }

    @GetMapping("/{id}")
    fun getDetail(@PathVariable id: String): ResponseEntity<ApiResponse<TenantDetailResponse>> {
        return ResponseEntity.ok(ApiResponse(data = tenantPlatformService.getDetail(id)))
    }

    @PatchMapping("/{id}/activate")
    fun activate(@PathVariable id: String): ResponseEntity<ApiResponse<TenantResponse>> {
        return ResponseEntity.ok(ApiResponse(data = tenantPlatformService.activate(id), message = "Tenant aktifleştirildi"))
    }

    @PatchMapping("/{id}/deactivate")
    fun deactivate(@PathVariable id: String): ResponseEntity<ApiResponse<TenantResponse>> {
        return ResponseEntity.ok(ApiResponse(data = tenantPlatformService.deactivate(id), message = "Tenant deaktifleştirildi"))
    }
}
