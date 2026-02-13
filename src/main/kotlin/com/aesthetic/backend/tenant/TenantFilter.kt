package com.aesthetic.backend.tenant

import com.aesthetic.backend.domain.tenant.Tenant
import com.aesthetic.backend.exception.TenantNotFoundException
import com.aesthetic.backend.repository.TenantRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.time.Instant

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TenantFilter(
    private val tenantRepository: TenantRepository,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val tenantCache: Cache<String, Tenant> = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build()

    private val exactExemptPaths = setOf(
        "/api/auth/login",
        "/api/auth/register",
        "/api/auth/refresh",
        "/api/auth/forgot-password",
        "/api/auth/reset-password"
    )

    private val prefixExemptPaths = listOf(
        "/api/platform/",
        "/api/webhooks/",
        "/swagger-ui/",
        "/v3/api-docs",
        "/actuator/"
    )

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        return uri in exactExemptPaths || prefixExemptPaths.any { uri.startsWith(it) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            val host = request.serverName
            val tenant = resolveTenant(host)

            TenantContext.setTenantId(tenant.id
                ?: throw IllegalStateException("Tenant ID null olamaz"))
            MDC.put("tenantId", tenant.id)
            MDC.put("tenantSlug", tenant.slug)

            filterChain.doFilter(request, response)
        } catch (e: TenantNotFoundException) {
            logger.warn("Tenant çözümlenemedi: {}", e.message)
            response.status = HttpServletResponse.SC_NOT_FOUND
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            val errorBody = mapOf(
                "success" to false,
                "error" to e.message,
                "code" to "TENANT_NOT_FOUND",
                "timestamp" to Instant.now().toString()
            )
            response.writer.write(objectMapper.writeValueAsString(errorBody))
        } finally {
            TenantContext.clear()
            MDC.remove("tenantId")
            MDC.remove("tenantSlug")
        }
    }

    private fun resolveTenant(host: String): Tenant {
        val slug = extractSubdomain(host)
        if (slug != null) {
            return tenantCache.get(slug) { key ->
                tenantRepository.findBySlugAndIsActiveTrue(key)
                    ?: throw TenantNotFoundException("Tenant bulunamadı veya aktif değil: $key")
            }
        }

        val cacheKey = "domain:$host"
        return tenantCache.get(cacheKey) { _ ->
            tenantRepository.findByCustomDomainAndIsActiveTrue(host)
                ?: throw TenantNotFoundException(
                    "Tenant belirlenemedi. Subdomain veya kayıtlı custom domain gerekli."
                )
        }
    }

    private fun extractSubdomain(host: String): String? {
        val parts = host.split(".")
        if (parts.size >= 3) {
            val subdomain = parts.first()
            if (subdomain !in setOf("www", "api", "admin")) {
                return subdomain
            }
        }
        return null
    }

    fun invalidateTenantCache(slug: String, customDomain: String? = null) {
        tenantCache.invalidate(slug)
        customDomain?.let { tenantCache.invalidate("domain:$it") }
        logger.info("Tenant cache invalidated: slug={}, customDomain={}", slug, customDomain)
    }
}
