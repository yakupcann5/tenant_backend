package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.CreateReviewRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.ReviewResponse
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.usecase.ReviewService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/client/reviews")
@PreAuthorize("hasAuthority('CLIENT')")
class ClientReviewController(
    private val reviewService: ReviewService
) {

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateReviewRequest,
        @AuthenticationPrincipal principal: UserPrincipal
    ): ResponseEntity<ApiResponse<ReviewResponse>> {
        val result = reviewService.createByClient(request, principal)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(data = result, message = "Değerlendirmeniz alındı"))
    }
}
