package com.aesthetic.backend.domain.schedule

import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity
@Table(
    name = "blocked_time_slots",
    indexes = [
        Index(name = "idx_bts_tenant_staff_date", columnList = "tenant_id, staff_id, date")
    ]
)
class BlockedTimeSlot(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id")
    var staff: User? = null,

    @Column(nullable = false)
    var date: LocalDate = LocalDate.now(),

    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime = LocalTime.of(9, 0),

    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime = LocalTime.of(10, 0),

    @Column(length = 500)
    var reason: String? = null,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
