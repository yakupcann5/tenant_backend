package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.CreateServiceRequest
import com.aesthetic.backend.dto.request.UpdateServiceRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.ServiceResponse
import com.aesthetic.backend.usecase.ServiceManagementService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/services")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class ServiceAdminController(
    private val serviceManagementService: ServiceManagementService
) {

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateServiceRequest
    ): ResponseEntity<ApiResponse<ServiceResponse>> {
        val service = serviceManagementService.create(request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(data = service, message = "Hizmet oluşturuldu"))
    }

    @GetMapping
    fun list(pageable: Pageable): ResponseEntity<PagedResponse<ServiceResponse>> {
        val services = serviceManagementService.listAll(pageable)
        return ResponseEntity.ok(services)
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<ApiResponse<ServiceResponse>> {
        val service = serviceManagementService.getById(id)
        return ResponseEntity.ok(ApiResponse(data = service))
    }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateServiceRequest
    ): ResponseEntity<ApiResponse<ServiceResponse>> {
        val service = serviceManagementService.update(id, request)
        return ResponseEntity.ok(ApiResponse(data = service, message = "Hizmet güncellendi"))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        serviceManagementService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
