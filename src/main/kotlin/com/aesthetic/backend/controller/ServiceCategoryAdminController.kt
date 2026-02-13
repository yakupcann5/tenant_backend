package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.CreateServiceCategoryRequest
import com.aesthetic.backend.dto.request.UpdateServiceCategoryRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.ServiceCategoryResponse
import com.aesthetic.backend.usecase.ServiceCategoryService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/service-categories")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class ServiceCategoryAdminController(
    private val serviceCategoryService: ServiceCategoryService
) {

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateServiceCategoryRequest
    ): ResponseEntity<ApiResponse<ServiceCategoryResponse>> {
        val category = serviceCategoryService.create(request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(data = category, message = "Kategori oluşturuldu"))
    }

    @GetMapping
    fun list(): ResponseEntity<ApiResponse<List<ServiceCategoryResponse>>> {
        val categories = serviceCategoryService.listAll()
        return ResponseEntity.ok(ApiResponse(data = categories))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<ApiResponse<ServiceCategoryResponse>> {
        val category = serviceCategoryService.getById(id)
        return ResponseEntity.ok(ApiResponse(data = category))
    }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateServiceCategoryRequest
    ): ResponseEntity<ApiResponse<ServiceCategoryResponse>> {
        val category = serviceCategoryService.update(id, request)
        return ResponseEntity.ok(ApiResponse(data = category, message = "Kategori güncellendi"))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        serviceCategoryService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
