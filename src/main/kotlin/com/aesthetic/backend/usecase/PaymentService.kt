package com.aesthetic.backend.usecase

import com.aesthetic.backend.config.IyzicoProperties
import com.aesthetic.backend.tenant.TenantContext
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class PaymentService(
    private val iyzicoProperties: IyzicoProperties,
    private val subscriptionService: SubscriptionService,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun verifySignature(payload: String, signature: String): Boolean {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(iyzicoProperties.secretKey.toByteArray(), "HmacSHA256"))
            val computed = Base64.getEncoder().encodeToString(mac.doFinal(payload.toByteArray()))
            MessageDigest.isEqual(computed.toByteArray(), signature.toByteArray())
        } catch (e: Exception) {
            logger.error("Signature verification failed", e)
            false
        }
    }

    fun processWebhookPayload(payload: String) {
        val json = objectMapper.readTree(payload)
        val eventType = json.path("eventType").asText("")
        val tenantId = json.path("tenantId").asText("")

        if (tenantId.isBlank()) {
            logger.warn("Webhook payload missing tenantId")
            return
        }

        when (eventType) {
            "payment_success" -> {
                subscriptionService.handlePaymentSuccess(tenantId)
                logger.info("Payment success processed: tenantId={}", tenantId)
            }
            "payment_failed" -> {
                subscriptionService.handlePaymentFailed(tenantId)
                logger.info("Payment failed processed: tenantId={}", tenantId)
            }
            "subscription_cancelled" -> {
                TenantContext.setTenantId(tenantId)
                try {
                    subscriptionService.cancelSubscription()
                } finally {
                    TenantContext.clear()
                }
                logger.info("Subscription cancelled processed: tenantId={}", tenantId)
            }
            else -> {
                logger.warn("Unknown webhook event type: {}", eventType)
            }
        }
    }

    fun extractEventId(payload: String): String {
        return try {
            val json = objectMapper.readTree(payload)
            json.path("eventId").asText(UUID.randomUUID().toString())
        } catch (e: Exception) {
            UUID.randomUUID().toString()
        }
    }

    fun extractEventType(payload: String): String {
        return try {
            val json = objectMapper.readTree(payload)
            json.path("eventType").asText("")
        } catch (e: Exception) {
            ""
        }
    }
}
