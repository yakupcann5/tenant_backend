package com.aesthetic.backend.domain.settings

import com.aesthetic.backend.tenant.TenantAwareEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(
    name = "site_settings",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_site_settings_tenant", columnNames = ["tenant_id"])
    ]
)
class SiteSettings(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "site_name")
    var siteName: String = "",

    var phone: String = "",

    var email: String = "",

    @Column(columnDefinition = "TEXT")
    var address: String = "",

    var whatsapp: String = "",

    var instagram: String = "",

    var facebook: String = "",

    var twitter: String = "",

    var youtube: String = "",

    @Column(name = "map_embed_url", length = 1000)
    var mapEmbedUrl: String = "",

    @Column(nullable = false, length = 50)
    var timezone: String = "Europe/Istanbul",

    @Column(nullable = false, length = 10)
    var locale: String = "tr",

    @Column(name = "cancellation_policy_hours", nullable = false)
    var cancellationPolicyHours: Int = 24,

    @Column(name = "default_slot_duration_minutes", nullable = false)
    var defaultSlotDurationMinutes: Int = 30,

    @Column(name = "auto_confirm_appointments", nullable = false)
    var autoConfirmAppointments: Boolean = false,

    @Column(name = "theme_settings", columnDefinition = "JSON")
    var themeSettings: String = "{}",

    @CreationTimestamp
    @Column(name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : TenantAwareEntity()
