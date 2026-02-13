package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.ProductResponse
import com.aesthetic.backend.usecase.ProductService
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/public/products")
class PublicProductController(
    private val productService: ProductService
) {

    @GetMapping
    fun list(pageable: Pageable): ResponseEntity<PagedResponse<ProductResponse>> {
        return ResponseEntity.ok(productService.listActive(pageable))
    }

    @GetMapping("/{slug}")
    fun getBySlug(@PathVariable slug: String): ResponseEntity<ApiResponse<ProductResponse>> {
        return ResponseEntity.ok(ApiResponse(data = productService.getBySlug(slug)))
    }
}
