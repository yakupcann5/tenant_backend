package com.aesthetic.backend.domain.product

import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(
    name = "products",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_products_slug_tenant", columnNames = ["slug", "tenant_id"])
    ]
)
class Product(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(nullable = false)
    var slug: String,

    @Column(nullable = false)
    var title: String,

    @Column(name = "short_description")
    var shortDescription: String = "",

    @Column(columnDefinition = "TEXT", nullable = false)
    var description: String,

    @Column(precision = 10, scale = 2, nullable = false)
    var price: BigDecimal = BigDecimal.ZERO,

    var currency: String = "TRY",

    var image: String? = null,

    @Column(name = "stock_quantity")
    var stockQuantity: Int? = null,

    @Column(name = "is_active")
    var isActive: Boolean = true,

    @Column(name = "sort_order")
    var sortOrder: Int = 0,

    @Column(name = "seo_title")
    var seoTitle: String? = null,

    @Column(name = "seo_description")
    var seoDescription: String? = null,

    @Column(name = "og_image")
    var ogImage: String? = null,

    @ElementCollection
    @CollectionTable(name = "product_features", joinColumns = [JoinColumn(name = "product_id")])
    @Column(name = "feature")
    @OrderColumn(name = "sort_order")
    var features: MutableList<String> = mutableListOf(),

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
