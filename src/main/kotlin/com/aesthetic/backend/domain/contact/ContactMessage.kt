package com.aesthetic.backend.domain.contact

import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(name = "contact_messages")
class ContactMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var email: String,

    var phone: String = "",

    var subject: String = "",

    @Column(columnDefinition = "TEXT", nullable = false)
    var message: String,

    @Column(name = "is_read")
    var isRead: Boolean = false,

    @Column(name = "read_at")
    var readAt: Instant? = null,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
