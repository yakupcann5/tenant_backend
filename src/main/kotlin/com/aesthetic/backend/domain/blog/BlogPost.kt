package com.aesthetic.backend.domain.blog

import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(
    name = "blog_posts",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_blog_posts_slug_tenant", columnNames = ["slug", "tenant_id"])
    ]
)
class BlogPost(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(nullable = false)
    var slug: String,

    @Column(nullable = false)
    var title: String,

    var summary: String = "",

    @Column(columnDefinition = "TEXT", nullable = false)
    var content: String,

    @Column(name = "cover_image")
    var coverImage: String? = null,

    @Column(name = "is_published")
    var isPublished: Boolean = false,

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    @Column(name = "seo_title")
    var seoTitle: String? = null,

    @Column(name = "seo_description")
    var seoDescription: String? = null,

    @Column(name = "og_image")
    var ogImage: String? = null,

    @Column(name = "author_name")
    var authorName: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    var author: User? = null,

    @ElementCollection
    @CollectionTable(name = "blog_post_tags", joinColumns = [JoinColumn(name = "blog_post_id")])
    @Column(name = "tag")
    @OrderColumn(name = "sort_order")
    var tags: MutableList<String> = mutableListOf(),

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
