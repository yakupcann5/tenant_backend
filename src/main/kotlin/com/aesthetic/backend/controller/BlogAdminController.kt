package com.aesthetic.backend.controller

import com.aesthetic.backend.config.RequiresModule
import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.dto.request.CreateBlogPostRequest
import com.aesthetic.backend.dto.request.UpdateBlogPostRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.BlogPostResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.usecase.BlogService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/blog")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
@RequiresModule(FeatureModule.BLOG)
class BlogAdminController(
    private val blogService: BlogService
) {

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateBlogPostRequest,
        @AuthenticationPrincipal principal: UserPrincipal
    ): ResponseEntity<ApiResponse<BlogPostResponse>> {
        val result = blogService.create(request, principal)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(data = result, message = "Blog yazısı oluşturuldu"))
    }

    @GetMapping
    fun list(pageable: Pageable): ResponseEntity<PagedResponse<BlogPostResponse>> {
        return ResponseEntity.ok(blogService.listAll(pageable))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<ApiResponse<BlogPostResponse>> {
        return ResponseEntity.ok(ApiResponse(data = blogService.getById(id)))
    }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateBlogPostRequest
    ): ResponseEntity<ApiResponse<BlogPostResponse>> {
        return ResponseEntity.ok(ApiResponse(data = blogService.update(id, request), message = "Blog yazısı güncellendi"))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        blogService.delete(id)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/{id}/publish")
    fun togglePublish(@PathVariable id: String): ResponseEntity<ApiResponse<BlogPostResponse>> {
        return ResponseEntity.ok(ApiResponse(data = blogService.togglePublish(id)))
    }
}
