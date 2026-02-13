package com.aesthetic.backend.security

import com.aesthetic.backend.config.JwtProperties
import com.aesthetic.backend.domain.user.Role
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JwtTokenProviderTest {

    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var jwtProperties: JwtProperties

    @BeforeEach
    fun setUp() {
        jwtProperties = JwtProperties(
            secret = "testSecretKeyThatIsAtLeast256BitsLongForHMACSHA256Algorithm!!",
            accessTokenExpiration = 3600000,
            refreshTokenExpiration = mapOf(
                "platform-admin" to 86400000L,
                "tenant-admin" to 2592000000L,
                "client" to 5184000000L,
                "staff" to 604800000L
            )
        )
        jwtTokenProvider = JwtTokenProvider(jwtProperties)
    }

    @Test
    fun `should generate valid access token with correct claims`() {
        val principal = createPrincipal(Role.TENANT_ADMIN)

        val token = jwtTokenProvider.generateAccessToken(principal)

        assertTrue(jwtTokenProvider.validateToken(token))
        assertEquals("user-1", jwtTokenProvider.getUserIdFromToken(token))
        assertEquals("tenant-1", jwtTokenProvider.getTenantIdFromToken(token))
        assertEquals("TENANT_ADMIN", jwtTokenProvider.getRoleFromToken(token))
        assertEquals("test@example.com", jwtTokenProvider.getEmailFromToken(token))
    }

    @Test
    fun `should generate refresh token with jti and type claim`() {
        val principal = createPrincipal(Role.CLIENT)

        val token = jwtTokenProvider.generateRefreshToken(principal)

        assertTrue(jwtTokenProvider.validateToken(token))
        assertNotNull(jwtTokenProvider.getJtiFromToken(token))
        assertEquals("refresh", jwtTokenProvider.getClaims(token)["type"])
    }

    @Test
    fun `should reject expired token`() {
        val shortLivedProps = JwtProperties(
            secret = jwtProperties.secret,
            accessTokenExpiration = 0,
            refreshTokenExpiration = jwtProperties.refreshTokenExpiration
        )
        val shortLivedProvider = JwtTokenProvider(shortLivedProps)
        val principal = createPrincipal(Role.TENANT_ADMIN)

        val token = shortLivedProvider.generateAccessToken(principal)
        Thread.sleep(10)

        assertFalse(jwtTokenProvider.validateToken(token))
    }

    @Test
    fun `should reject token with invalid signature`() {
        assertFalse(jwtTokenProvider.validateToken("invalid.token.here"))
    }

    @Test
    fun `should reject malformed token`() {
        assertFalse(jwtTokenProvider.validateToken(""))
        assertFalse(jwtTokenProvider.validateToken("not-a-jwt"))
    }

    @Test
    fun `should reject token signed with different key`() {
        val otherProps = JwtProperties(
            secret = "anotherSecretKeyThatIsAtLeast256BitsLongForHMACSHA256Algorithm!!",
            accessTokenExpiration = 3600000,
            refreshTokenExpiration = jwtProperties.refreshTokenExpiration
        )
        val otherProvider = JwtTokenProvider(otherProps)
        val principal = createPrincipal(Role.TENANT_ADMIN)

        val token = otherProvider.generateAccessToken(principal)

        assertFalse(jwtTokenProvider.validateToken(token))
    }

    @Test
    fun `should create authentication from token`() {
        val principal = createPrincipal(Role.STAFF)

        val token = jwtTokenProvider.generateAccessToken(principal)
        val auth = jwtTokenProvider.getAuthentication(token)

        assertTrue(auth.isAuthenticated)
        val authPrincipal = auth.principal as UserPrincipal
        assertEquals("user-1", authPrincipal.id)
        assertEquals("tenant-1", authPrincipal.tenantId)
        assertEquals(Role.STAFF, authPrincipal.role)
        assertTrue(auth.authorities.any { it.authority == "STAFF" })
    }

    @Test
    fun `should include correct claims for cross-tenant validation`() {
        val principal = UserPrincipal(
            id = "user-1",
            email = "test@example.com",
            tenantId = "tenant-abc",
            role = Role.TENANT_ADMIN,
            passwordHash = ""
        )

        val token = jwtTokenProvider.generateAccessToken(principal)
        val claims = jwtTokenProvider.getClaims(token)

        assertEquals("user-1", claims.subject)
        assertEquals("tenant-abc", claims["tenantId"])
        assertEquals("TENANT_ADMIN", claims["role"])
        assertEquals("test@example.com", claims["email"])
    }

    @Test
    fun `refresh token should not have jti in access token`() {
        val principal = createPrincipal(Role.CLIENT)

        val accessToken = jwtTokenProvider.generateAccessToken(principal)

        assertNull(jwtTokenProvider.getJtiFromToken(accessToken))
    }

    @Test
    fun `each refresh should generate unique jti`() {
        val principal = createPrincipal(Role.CLIENT)

        val token1 = jwtTokenProvider.generateRefreshToken(principal)
        val token2 = jwtTokenProvider.generateRefreshToken(principal)

        val jti1 = jwtTokenProvider.getJtiFromToken(token1)
        val jti2 = jwtTokenProvider.getJtiFromToken(token2)

        assertNotNull(jti1)
        assertNotNull(jti2)
        assertNotEquals(jti1, jti2)
    }

    private fun createPrincipal(role: Role) = UserPrincipal(
        id = "user-1",
        email = "test@example.com",
        tenantId = "tenant-1",
        role = role,
        passwordHash = ""
    )
}
