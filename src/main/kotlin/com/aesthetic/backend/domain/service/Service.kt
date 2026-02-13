package com.aesthetic.backend.domain.service

import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(
    name = "services",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_services_slug_tenant", columnNames = ["slug", "tenant_id"])
    ]
)
class Service(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(nullable = false)
    var slug: String,

    @Column(nullable = false)
    var title: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    var category: ServiceCategory? = null,

    @Column(name = "short_description", length = 500)
    var shortDescription: String = "",

    @Column(columnDefinition = "TEXT")
    var description: String = "",

    @Column(precision = 10, scale = 2, nullable = false)
    var price: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, length = 10)
    var currency: String = "TRY",

    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int = 30,

    @Column(name = "buffer_minutes", nullable = false)
    var bufferMinutes: Int = 0,

    var image: String? = null,

    @ElementCollection
    @CollectionTable(name = "service_benefits", joinColumns = [JoinColumn(name = "service_id")])
    @Column(name = "benefit")
    @OrderColumn(name = "sort_order")
    var benefits: MutableList<String> = mutableListOf(),

    @ElementCollection
    @CollectionTable(name = "service_process_steps", joinColumns = [JoinColumn(name = "service_id")])
    @Column(name = "step")
    @OrderColumn(name = "sort_order")
    var processSteps: MutableList<String> = mutableListOf(),

    @Column(columnDefinition = "TEXT")
    var recovery: String? = null,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "meta_title")
    var metaTitle: String? = null,

    @Column(name = "meta_description", length = 500)
    var metaDescription: String? = null,

    @Column(name = "og_image")
    var ogImage: String? = null,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
