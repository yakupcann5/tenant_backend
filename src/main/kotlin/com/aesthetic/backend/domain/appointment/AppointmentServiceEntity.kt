package com.aesthetic.backend.domain.appointment

import com.aesthetic.backend.domain.service.Service
import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "appointment_services")
class AppointmentServiceEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false)
    var appointment: Appointment? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    var service: Service? = null,

    @Column(precision = 10, scale = 2)
    var price: BigDecimal = BigDecimal.ZERO,

    @Column(name = "duration_minutes")
    var durationMinutes: Int = 0,

    @Column(name = "sort_order")
    var sortOrder: Int = 0
) : TenantAwareEntity()
