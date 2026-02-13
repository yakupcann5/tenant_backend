package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.GalleryItemResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.usecase.GalleryService
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/public/gallery")
class PublicGalleryController(
    private val galleryService: GalleryService
) {

    @GetMapping
    fun list(pageable: Pageable): ResponseEntity<PagedResponse<GalleryItemResponse>> {
        return ResponseEntity.ok(galleryService.listActive(pageable))
    }
}
