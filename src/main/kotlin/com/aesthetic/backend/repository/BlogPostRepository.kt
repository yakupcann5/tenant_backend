package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.blog.BlogPost
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface BlogPostRepository : JpaRepository<BlogPost, String> {
    fun findBySlugAndTenantId(slug: String, tenantId: String): BlogPost?
    fun findAllByTenantId(tenantId: String, pageable: Pageable): Page<BlogPost>
    fun findAllByTenantIdAndIsPublishedTrue(tenantId: String, pageable: Pageable): Page<BlogPost>

    @Query("""
        SELECT b FROM BlogPost b
        LEFT JOIN FETCH b.author
        WHERE b.id = :id
    """)
    fun findByIdWithAuthor(@Param("id") id: String): BlogPost?
}
