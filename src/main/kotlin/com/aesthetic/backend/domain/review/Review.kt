package com.aesthetic.backend.domain.review

import com.aesthetic.backend.domain.appointment.Appointment
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(name = "reviews")
class Review(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    var appointment: Appointment? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    var service: com.aesthetic.backend.domain.service.Service? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    var client: User? = null,

    @Column(name = "client_name")
    var clientName: String = "",

    @Column(nullable = false)
    var rating: Int,

    @Column(columnDefinition = "TEXT", nullable = false)
    var comment: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false)
    var approvalStatus: ReviewApprovalStatus = ReviewApprovalStatus.PENDING,

    @Column(name = "admin_response", columnDefinition = "TEXT")
    var adminResponse: String? = null,

    @Column(name = "admin_response_at")
    var adminResponseAt: Instant? = null,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
