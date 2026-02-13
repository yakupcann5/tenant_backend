package com.aesthetic.backend.security

import com.aesthetic.backend.config.JwtProperties
import com.aesthetic.backend.domain.user.Role
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.*
import javax.crypto.SecretKey
import io.jsonwebtoken.security.Keys

@Component
class JwtTokenProvider(private val jwtProperties: JwtProperties) {

    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray(StandardCharsets.UTF_8))
    }

    fun generateAccessToken(principal: UserPrincipal): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject(principal.id)
            .claim("tenantId", principal.tenantId)
            .claim("role", principal.role.name)
            .claim("email", principal.email)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(jwtProperties.accessTokenExpiration)))
            .signWith(key)
            .compact()
    }

    fun generateRefreshToken(principal: UserPrincipal): String {
        val now = Instant.now()
        val refreshExpiration = getRefreshTokenExpiration(principal.role)
        return Jwts.builder()
            .subject(principal.id)
            .id(UUID.randomUUID().toString())
            .claim("tenantId", principal.tenantId)
            .claim("role", principal.role.name)
            .claim("type", "refresh")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(refreshExpiration)))
            .signWith(key)
            .compact()
    }

    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getClaims(token: String): Claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload

    fun getUserIdFromToken(token: String): String = getClaims(token).subject

    fun getTenantIdFromToken(token: String): String? = getClaims(token)["tenantId"] as? String

    fun getRoleFromToken(token: String): String? = getClaims(token)["role"] as? String

    fun getEmailFromToken(token: String): String? = getClaims(token)["email"] as? String

    fun getJtiFromToken(token: String): String? = getClaims(token).id

    fun getAuthentication(token: String): Authentication {
        val claims = getClaims(token)
        val principal = UserPrincipal(
            id = claims.subject,
            email = claims["email"] as? String ?: "",
            tenantId = claims["tenantId"] as? String ?: "",
            role = Role.valueOf(claims["role"] as String),
            passwordHash = ""
        )
        return UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    }

    private fun getRefreshTokenExpiration(role: Role): Long {
        return when (role) {
            Role.PLATFORM_ADMIN -> jwtProperties.refreshTokenExpiration["platform-admin"] ?: 86400000L
            Role.TENANT_ADMIN -> jwtProperties.refreshTokenExpiration["tenant-admin"] ?: 2592000000L
            Role.CLIENT -> jwtProperties.refreshTokenExpiration["client"] ?: 5184000000L
            Role.STAFF -> jwtProperties.refreshTokenExpiration["staff"] ?: 604800000L
        }
    }
}
