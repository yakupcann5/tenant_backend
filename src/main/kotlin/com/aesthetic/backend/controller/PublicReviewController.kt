package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.ReviewResponse
import com.aesthetic.backend.usecase.ReviewService
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/public/reviews")
class PublicReviewController(
    private val reviewService: ReviewService
) {

    @GetMapping
    fun list(pageable: Pageable): ResponseEntity<PagedResponse<ReviewResponse>> {
        return ResponseEntity.ok(reviewService.listApproved(pageable))
    }
}
