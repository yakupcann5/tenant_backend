package com.aesthetic.backend.controller

import com.aesthetic.backend.config.RequiresModule
import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.dto.request.CreateProductRequest
import com.aesthetic.backend.dto.request.UpdateProductRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.ProductResponse
import com.aesthetic.backend.usecase.ProductService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/products")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
@RequiresModule(FeatureModule.PRODUCTS)
class ProductAdminController(
    private val productService: ProductService
) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateProductRequest): ResponseEntity<ApiResponse<ProductResponse>> {
        val result = productService.create(request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(data = result, message = "Ürün oluşturuldu"))
    }

    @GetMapping
    fun list(pageable: Pageable): ResponseEntity<PagedResponse<ProductResponse>> {
        return ResponseEntity.ok(productService.listAll(pageable))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<ApiResponse<ProductResponse>> {
        return ResponseEntity.ok(ApiResponse(data = productService.getById(id)))
    }

    @PatchMapping("/{id}")
    fun update(@PathVariable id: String, @Valid @RequestBody request: UpdateProductRequest): ResponseEntity<ApiResponse<ProductResponse>> {
        return ResponseEntity.ok(ApiResponse(data = productService.update(id, request), message = "Ürün güncellendi"))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        productService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
