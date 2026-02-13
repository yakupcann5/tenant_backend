package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.payment.ProcessedWebhookEvent
import org.springframework.data.jpa.repository.JpaRepository

interface ProcessedWebhookEventRepository : JpaRepository<ProcessedWebhookEvent, String> {
    fun existsByEventId(eventId: String): Boolean
}
