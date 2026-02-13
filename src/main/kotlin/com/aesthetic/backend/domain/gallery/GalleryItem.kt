package com.aesthetic.backend.domain.gallery

import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(name = "gallery_items")
class GalleryItem(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    var title: String = "",

    @Column(columnDefinition = "TEXT", nullable = false)
    var description: String = "",

    @Column(name = "image_url", nullable = false)
    var imageUrl: String,

    @Column(name = "before_image_url")
    var beforeImageUrl: String? = null,

    @Column(name = "after_image_url")
    var afterImageUrl: String? = null,

    @Column(name = "is_active")
    var isActive: Boolean = true,

    @Column(name = "sort_order")
    var sortOrder: Int = 0,

    var category: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    var service: com.aesthetic.backend.domain.service.Service? = null,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
