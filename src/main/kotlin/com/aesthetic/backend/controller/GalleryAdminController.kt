package com.aesthetic.backend.controller

import com.aesthetic.backend.config.RequiresModule
import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.dto.request.CreateGalleryItemRequest
import com.aesthetic.backend.dto.request.UpdateGalleryItemRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.GalleryItemResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.usecase.GalleryService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/gallery")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
@RequiresModule(FeatureModule.GALLERY)
class GalleryAdminController(
    private val galleryService: GalleryService
) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateGalleryItemRequest): ResponseEntity<ApiResponse<GalleryItemResponse>> {
        val result = galleryService.create(request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(data = result, message = "Galeri öğesi oluşturuldu"))
    }

    @GetMapping
    fun list(pageable: Pageable): ResponseEntity<PagedResponse<GalleryItemResponse>> {
        return ResponseEntity.ok(galleryService.listAll(pageable))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<ApiResponse<GalleryItemResponse>> {
        return ResponseEntity.ok(ApiResponse(data = galleryService.getById(id)))
    }

    @PatchMapping("/{id}")
    fun update(@PathVariable id: String, @Valid @RequestBody request: UpdateGalleryItemRequest): ResponseEntity<ApiResponse<GalleryItemResponse>> {
        return ResponseEntity.ok(ApiResponse(data = galleryService.update(id, request), message = "Galeri öğesi güncellendi"))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        galleryService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
