package com.aesthetic.backend.domain.patient

import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "treatment_histories")
class TreatmentHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    var client: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by_id")
    var performedBy: User? = null,

    @Column(name = "treatment_date", nullable = false)
    var treatmentDate: LocalDate,

    @Column(nullable = false)
    var title: String,

    @Column(columnDefinition = "TEXT")
    var description: String = "",

    @Column(columnDefinition = "TEXT")
    var notes: String = "",

    @Column(name = "before_image")
    var beforeImage: String? = null,

    @Column(name = "after_image")
    var afterImage: String? = null,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
