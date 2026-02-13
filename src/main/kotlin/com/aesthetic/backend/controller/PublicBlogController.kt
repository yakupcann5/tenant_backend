package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.BlogPostResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.usecase.BlogService
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/public/blog")
class PublicBlogController(
    private val blogService: BlogService
) {

    @GetMapping
    fun list(pageable: Pageable): ResponseEntity<PagedResponse<BlogPostResponse>> {
        return ResponseEntity.ok(blogService.listPublished(pageable))
    }

    @GetMapping("/{slug}")
    fun getBySlug(@PathVariable slug: String): ResponseEntity<ApiResponse<BlogPostResponse>> {
        return ResponseEntity.ok(ApiResponse(data = blogService.getBySlug(slug)))
    }
}
