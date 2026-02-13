package com.aesthetic.backend.domain.schedule

import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

@Entity
@Table(
    name = "working_hours",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_working_hours_tenant_staff_day",
            columnNames = ["tenant_id", "staff_id", "day_of_week"]
        )
    ]
)
class WorkingHours(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id")
    var staff: User? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false)
    var dayOfWeek: DayOfWeek,

    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime = LocalTime.of(9, 0),

    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime = LocalTime.of(18, 0),

    @Column(name = "break_start_time")
    var breakStartTime: LocalTime? = null,

    @Column(name = "break_end_time")
    var breakEndTime: LocalTime? = null,

    @Column(name = "is_open", nullable = false)
    var isOpen: Boolean = true,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
