package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.blog.BlogPost
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.CreateBlogPostRequest
import com.aesthetic.backend.dto.request.UpdateBlogPostRequest
import com.aesthetic.backend.dto.response.BlogPostResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.BlogPostRepository
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer
import java.time.Instant

@Service
class BlogService(
    private val blogPostRepository: BlogPostRepository,
    private val entityManager: EntityManager,
    private val userRepository: UserRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(request: CreateBlogPostRequest, principal: UserPrincipal): BlogPostResponse {
        val tenantId = TenantContext.getTenantId()
        val author = userRepository.findById(principal.id)
            .orElseThrow { ResourceNotFoundException("Kullanıcı bulunamadı") }

        val slug = generateSlug(request.title, tenantId)

        val post = BlogPost(
            slug = slug,
            title = request.title,
            summary = request.summary,
            content = request.content,
            coverImage = request.coverImage,
            seoTitle = request.seoTitle,
            seoDescription = request.seoDescription,
            ogImage = request.ogImage,
            authorName = "${author.firstName} ${author.lastName}".trim(),
            author = author,
            tags = request.tags.toMutableList()
        )
        return blogPostRepository.save(post).toResponse()
    }

    @Transactional
    fun update(id: String, request: UpdateBlogPostRequest): BlogPostResponse {
        val post = blogPostRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Blog yazısı bulunamadı: $id") }

        request.title?.let {
            post.title = it
            post.slug = generateSlug(it, post.tenantId)
        }
        request.summary?.let { post.summary = it }
        request.content?.let { post.content = it }
        request.coverImage?.let { post.coverImage = it }
        request.seoTitle?.let { post.seoTitle = it }
        request.seoDescription?.let { post.seoDescription = it }
        request.ogImage?.let { post.ogImage = it }
        request.tags?.let {
            post.tags.clear()
            post.tags.addAll(it)
        }

        return blogPostRepository.save(post).toResponse()
    }

    @Transactional
    fun delete(id: String) {
        val post = blogPostRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Blog yazısı bulunamadı: $id") }
        blogPostRepository.delete(post)
    }

    @Transactional(readOnly = true)
    fun getById(id: String): BlogPostResponse {
        return blogPostRepository.findByIdWithAuthor(id)?.toResponse()
            ?: throw ResourceNotFoundException("Blog yazısı bulunamadı: $id")
    }

    @Transactional(readOnly = true)
    fun getBySlug(slug: String): BlogPostResponse {
        val tenantId = TenantContext.getTenantId()
        return blogPostRepository.findBySlugAndTenantId(slug, tenantId)?.toResponse()
            ?: throw ResourceNotFoundException("Blog yazısı bulunamadı: $slug")
    }

    @Transactional(readOnly = true)
    fun listAll(pageable: Pageable): PagedResponse<BlogPostResponse> {
        val tenantId = TenantContext.getTenantId()
        return blogPostRepository.findAllByTenantId(tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun listPublished(pageable: Pageable): PagedResponse<BlogPostResponse> {
        val tenantId = TenantContext.getTenantId()
        return blogPostRepository.findAllByTenantIdAndIsPublishedTrue(tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }

    @Transactional
    fun togglePublish(id: String): BlogPostResponse {
        val post = blogPostRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Blog yazısı bulunamadı: $id") }

        post.isPublished = !post.isPublished
        if (post.isPublished && post.publishedAt == null) {
            post.publishedAt = Instant.now()
        }

        return blogPostRepository.save(post).toResponse()
    }

    private fun generateSlug(title: String, tenantId: String): String {
        val base = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace("[^\\p{ASCII}]".toRegex(), "")
            .lowercase()
            .replace("[^a-z0-9]+".toRegex(), "-")
            .trim('-')

        var slug = base
        var counter = 1
        while (blogPostRepository.findBySlugAndTenantId(slug, tenantId) != null) {
            slug = "$base-$counter"
            counter++
        }
        return slug
    }
}
