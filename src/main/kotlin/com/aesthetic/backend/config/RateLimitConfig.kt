package com.aesthetic.backend.config

import java.time.Duration

data class RateLimitRule(
    val pathPrefix: String,
    val limit: Long,
    val period: Duration
)

object RateLimitRules {
    val rules: List<RateLimitRule> = listOf(
        RateLimitRule("/api/auth/login", 5, Duration.ofMinutes(1)),
        RateLimitRule("/api/auth/register", 5, Duration.ofMinutes(1)),
        RateLimitRule("/api/auth/forgot-password", 3, Duration.ofMinutes(1)),
        RateLimitRule("/api/public/contact", 3, Duration.ofMinutes(1)),
        RateLimitRule("/api/public/appointments", 10, Duration.ofMinutes(1)),
        RateLimitRule("/api/admin/upload", 20, Duration.ofMinutes(1)),
        RateLimitRule("/api/admin/", 100, Duration.ofMinutes(1)),
        RateLimitRule("/api/public/", 200, Duration.ofMinutes(1))
    )
}
