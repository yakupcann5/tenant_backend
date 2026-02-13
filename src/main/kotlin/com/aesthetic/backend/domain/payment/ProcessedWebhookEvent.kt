package com.aesthetic.backend.domain.payment

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "processed_webhook_events",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_processed_webhook_event_id", columnNames = ["event_id"])
    ]
)
class ProcessedWebhookEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "event_id", nullable = false, unique = true)
    var eventId: String = "",

    @Column(nullable = false, length = 50)
    var provider: String = "iyzico",

    @Column(name = "event_type", length = 100)
    var eventType: String = "",

    @Column(columnDefinition = "JSON")
    var payload: String? = null,

    @Column(name = "processed_at", nullable = false)
    var processedAt: Instant = Instant.now()
)
