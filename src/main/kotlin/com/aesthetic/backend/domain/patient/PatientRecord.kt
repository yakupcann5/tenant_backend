package com.aesthetic.backend.domain.patient

import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(
    name = "patient_records",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_patient_records_tenant_client", columnNames = ["tenant_id", "client_id"])
    ]
)
class PatientRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    var client: User,

    @Column(name = "blood_type", length = 10)
    var bloodType: String? = null,

    @Column(columnDefinition = "TEXT")
    var allergies: String = "",

    @Column(name = "chronic_conditions", columnDefinition = "TEXT")
    var chronicConditions: String = "",

    @Column(name = "current_medications", columnDefinition = "TEXT")
    var currentMedications: String = "",

    @Column(name = "medical_notes", columnDefinition = "TEXT")
    var medicalNotes: String = "",

    @Column(name = "extra_data", columnDefinition = "JSON")
    var extraData: String? = null,

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
