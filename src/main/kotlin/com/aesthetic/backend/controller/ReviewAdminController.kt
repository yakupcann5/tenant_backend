package com.aesthetic.backend.controller

import com.aesthetic.backend.config.RequiresModule
import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.dto.request.AdminRespondReviewRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.ReviewResponse
import com.aesthetic.backend.usecase.ReviewService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/reviews")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
@RequiresModule(FeatureModule.REVIEWS)
class ReviewAdminController(
    private val reviewService: ReviewService
) {

    @GetMapping
    fun list(pageable: Pageable): ResponseEntity<PagedResponse<ReviewResponse>> {
        return ResponseEntity.ok(reviewService.listAll(pageable))
    }

    @PatchMapping("/{id}/approve")
    fun approve(@PathVariable id: String): ResponseEntity<ApiResponse<ReviewResponse>> {
        return ResponseEntity.ok(ApiResponse(data = reviewService.approve(id), message = "Değerlendirme onaylandı"))
    }

    @PatchMapping("/{id}/reject")
    fun reject(@PathVariable id: String): ResponseEntity<ApiResponse<ReviewResponse>> {
        return ResponseEntity.ok(ApiResponse(data = reviewService.reject(id), message = "Değerlendirme reddedildi"))
    }

    @PostMapping("/{id}/response")
    fun addResponse(@PathVariable id: String, @Valid @RequestBody request: AdminRespondReviewRequest): ResponseEntity<ApiResponse<ReviewResponse>> {
        return ResponseEntity.ok(ApiResponse(data = reviewService.addAdminResponse(id, request), message = "Yanıt eklendi"))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        reviewService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
