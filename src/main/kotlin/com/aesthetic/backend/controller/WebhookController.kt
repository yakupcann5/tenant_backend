package com.aesthetic.backend.controller

import com.aesthetic.backend.domain.payment.ProcessedWebhookEvent
import com.aesthetic.backend.repository.ProcessedWebhookEventRepository
import com.aesthetic.backend.usecase.PaymentService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/api/webhooks")
class WebhookController(
    private val paymentService: PaymentService,
    private val processedWebhookEventRepository: ProcessedWebhookEventRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping("/iyzico")
    fun handleIyzicoWebhook(
        @RequestBody payload: String,
        @RequestHeader("X-IYZ-SIGNATURE", required = false) signature: String?
    ): ResponseEntity<String> {
        // 1. Signature verification
        if (signature == null || !paymentService.verifySignature(payload, signature)) {
            logger.warn("Invalid webhook signature")
            return ResponseEntity.status(401).body("Invalid signature")
        }

        // 2. Idempotency check
        val eventId = paymentService.extractEventId(payload)
        if (processedWebhookEventRepository.existsByEventId(eventId)) {
            return ResponseEntity.ok("OK")
        }

        // 3. Process
        try {
            paymentService.processWebhookPayload(payload)

            // 4. Save processed event
            processedWebhookEventRepository.save(
                ProcessedWebhookEvent(
                    eventId = eventId,
                    provider = "iyzico",
                    eventType = paymentService.extractEventType(payload),
                    payload = payload,
                    processedAt = Instant.now()
                )
            )
        } catch (e: Exception) {
            logger.error("Webhook processing failed: eventId={}", eventId, e)
            return ResponseEntity.status(500).body("Processing failed")
        }

        return ResponseEntity.ok("OK")
    }
}
