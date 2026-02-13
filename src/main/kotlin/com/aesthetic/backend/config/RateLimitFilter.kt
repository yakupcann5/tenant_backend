package com.aesthetic.backend.config

import com.aesthetic.backend.dto.response.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RateLimitFilter(
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    private val buckets = ConcurrentHashMap<String, Bucket>()

    private val exemptPrefixes = listOf(
        "/swagger-ui/",
        "/v3/api-docs",
        "/actuator/",
        "/api/webhooks/"
    )

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        return exemptPrefixes.any { uri.startsWith(it) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val uri = request.requestURI
        val rule = RateLimitRules.rules.firstOrNull { uri.startsWith(it.pathPrefix) }

        if (rule == null) {
            filterChain.doFilter(request, response)
            return
        }

        val clientIp = getClientIp(request)
        val bucketKey = "$clientIp:${rule.pathPrefix}"
        val bucket = buckets.computeIfAbsent(bucketKey) { createBucket(rule) }

        val probe = bucket.tryConsumeAndReturnRemaining(1)
        if (probe.isConsumed) {
            response.setHeader("X-Rate-Limit-Remaining", probe.remainingTokens.toString())
            filterChain.doFilter(request, response)
        } else {
            response.status = 429
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            response.setHeader("Retry-After", (probe.nanosToWaitForRefill / 1_000_000_000).toString())
            val errorBody = mapOf(
                "success" to false,
                "error" to "Çok fazla istek gönderildi. Lütfen bir süre bekleyin.",
                "code" to ErrorCode.RATE_LIMIT_EXCEEDED.name,
                "timestamp" to Instant.now().toString()
            )
            response.writer.write(objectMapper.writeValueAsString(errorBody))
        }
    }

    private fun createBucket(rule: RateLimitRule): Bucket {
        val bandwidth = Bandwidth.builder()
            .capacity(rule.limit)
            .refillGreedy(rule.limit, rule.period)
            .build()
        return Bucket.builder().addLimit(bandwidth).build()
    }

    private fun getClientIp(request: HttpServletRequest): String {
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        return if (!xForwardedFor.isNullOrBlank()) {
            xForwardedFor.split(",").first().trim()
        } else {
            request.remoteAddr
        }
    }
}
