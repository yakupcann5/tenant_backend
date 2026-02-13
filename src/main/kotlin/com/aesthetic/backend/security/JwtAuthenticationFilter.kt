package com.aesthetic.backend.security

import com.aesthetic.backend.tenant.TenantContext
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant

class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val token = extractToken(request)

        if (token != null && jwtTokenProvider.validateToken(token)) {
            val jwtTenantId = jwtTokenProvider.getTenantIdFromToken(token)
            val contextTenantId = TenantContext.getTenantIdOrNull()

            if (contextTenantId != null && jwtTenantId != null && contextTenantId != jwtTenantId) {
                log.warn(
                    "Cross-tenant erişim engellendi. JWT tenant: {}, Context tenant: {}",
                    jwtTenantId, contextTenantId
                )
                response.status = HttpServletResponse.SC_FORBIDDEN
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.characterEncoding = "UTF-8"
                val errorBody = mapOf(
                    "success" to false,
                    "error" to "Cross-tenant erişim engellendi.",
                    "code" to "FORBIDDEN",
                    "timestamp" to Instant.now().toString()
                )
                response.writer.write(objectMapper.writeValueAsString(errorBody))
                return
            }

            val authentication = jwtTokenProvider.getAuthentication(token)
            SecurityContextHolder.getContext().authentication = authentication
        }

        filterChain.doFilter(request, response)
    }

    private fun extractToken(request: HttpServletRequest): String? {
        val bearerToken = request.getHeader("Authorization")
        return if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken.substring(7)
        } else null
    }
}
