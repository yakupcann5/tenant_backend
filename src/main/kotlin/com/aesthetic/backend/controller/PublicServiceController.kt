package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.ServiceCategoryResponse
import com.aesthetic.backend.dto.response.ServiceResponse
import com.aesthetic.backend.usecase.ServiceCategoryService
import com.aesthetic.backend.usecase.ServiceManagementService
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/public")
class PublicServiceController(
    private val serviceManagementService: ServiceManagementService,
    private val serviceCategoryService: ServiceCategoryService
) {

    @GetMapping("/services")
    fun listServices(pageable: Pageable): ResponseEntity<PagedResponse<ServiceResponse>> {
        val services = serviceManagementService.listActive(pageable)
        return ResponseEntity.ok(services)
    }

    @GetMapping("/services/{slug}")
    fun getServiceBySlug(@PathVariable slug: String): ResponseEntity<ApiResponse<ServiceResponse>> {
        val service = serviceManagementService.getBySlug(slug)
        return ResponseEntity.ok(ApiResponse(data = service))
    }

    @GetMapping("/service-categories")
    fun listCategories(): ResponseEntity<ApiResponse<List<ServiceCategoryResponse>>> {
        val categories = serviceCategoryService.listActive()
        return ResponseEntity.ok(ApiResponse(data = categories))
    }
}
