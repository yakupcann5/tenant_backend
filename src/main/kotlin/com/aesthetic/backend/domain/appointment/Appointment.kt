package com.aesthetic.backend.domain.appointment

import com.aesthetic.backend.domain.service.Service
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity
@Table(
    name = "appointments",
    indexes = [
        Index(name = "idx_appt_conflict", columnList = "tenant_id, staff_id, date, start_time, end_time, status"),
        Index(name = "idx_appt_tenant_date", columnList = "tenant_id, date"),
        Index(name = "idx_appt_status", columnList = "tenant_id, status"),
        Index(name = "idx_appt_client", columnList = "tenant_id, client_id")
    ]
)
class Appointment(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    var client: User? = null,

    @Column(name = "client_name", nullable = false)
    var clientName: String = "",

    @Column(name = "client_email", nullable = false)
    var clientEmail: String = "",

    @Column(name = "client_phone", nullable = false)
    var clientPhone: String = "",

    @OneToMany(mappedBy = "appointment", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    var services: MutableList<AppointmentServiceEntity> = mutableListOf(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_service_id")
    var primaryService: Service? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id")
    var staff: User? = null,

    @Column(nullable = false)
    var date: LocalDate = LocalDate.now(),

    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime = LocalTime.of(9, 0),

    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime = LocalTime.of(10, 0),

    @Column(name = "total_duration_minutes")
    var totalDurationMinutes: Int = 0,

    @Column(name = "total_price", precision = 10, scale = 2)
    var totalPrice: BigDecimal = BigDecimal.ZERO,

    @Column(columnDefinition = "TEXT")
    var notes: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AppointmentStatus = AppointmentStatus.PENDING,

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null,

    @Column(name = "cancellation_reason", length = 500)
    var cancellationReason: String? = null,

    @Column(name = "recurring_group_id")
    var recurringGroupId: String? = null,

    @Column(name = "recurrence_rule", length = 20)
    var recurrenceRule: String? = null,

    @Column(name = "reminder_24h_sent")
    var reminder24hSent: Boolean = false,

    @Column(name = "reminder_1h_sent")
    var reminder1hSent: Boolean = false,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    @Version
    var version: Long = 0
) : TenantAwareEntity()
