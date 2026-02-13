package com.aesthetic.backend.security

import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.tenant.TenantContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

@ExtendWith(MockKExtension::class)
class JwtAuthenticationFilterTest {

    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val objectMapper = ObjectMapper().registerModule(JavaTimeModule())
    private lateinit var filter: JwtAuthenticationFilter

    @BeforeEach
    fun setUp() {
        filter = JwtAuthenticationFilter(jwtTokenProvider, objectMapper)
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        TenantContext.clear()
    }

    @Test
    fun `should authenticate with valid token`() {
        val token = "valid-token"
        val principal = UserPrincipal("user-1", "test@example.com", "tenant-1", Role.TENANT_ADMIN, "")
        val auth = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)

        TenantContext.setTenantId("tenant-1")

        every { jwtTokenProvider.validateToken(token) } returns true
        every { jwtTokenProvider.getTenantIdFromToken(token) } returns "tenant-1"
        every { jwtTokenProvider.getAuthentication(token) } returns auth

        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val filterChain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(request, response, filterChain)

        verify { filterChain.doFilter(request, response) }
        assertNotNull(SecurityContextHolder.getContext().authentication)
        assertEquals("user-1", (SecurityContextHolder.getContext().authentication.principal as UserPrincipal).id)
    }

    @Test
    fun `should reject cross-tenant access`() {
        val token = "valid-token"

        TenantContext.setTenantId("tenant-1")

        every { jwtTokenProvider.validateToken(token) } returns true
        every { jwtTokenProvider.getTenantIdFromToken(token) } returns "tenant-2"

        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val filterChain = mockk<FilterChain>()

        filter.doFilter(request, response, filterChain)

        assertEquals(403, response.status)
        assertTrue(response.contentAsString.contains("Cross-tenant"))
        verify(exactly = 0) { filterChain.doFilter(any(), any()) }
    }

    @Test
    fun `should pass through without token`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val filterChain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(request, response, filterChain)

        verify { filterChain.doFilter(request, response) }
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `should pass through with invalid token`() {
        val token = "invalid-token"

        every { jwtTokenProvider.validateToken(token) } returns false

        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val filterChain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(request, response, filterChain)

        verify { filterChain.doFilter(request, response) }
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `should ignore non-bearer authorization header`() {
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Basic dXNlcjpwYXNz")
        }
        val response = MockHttpServletResponse()
        val filterChain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(request, response, filterChain)

        verify { filterChain.doFilter(request, response) }
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `should allow access when no tenant context and valid token`() {
        val token = "valid-token"
        val principal = UserPrincipal("user-1", "admin@platform.com", "", Role.PLATFORM_ADMIN, "")
        val auth = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)

        every { jwtTokenProvider.validateToken(token) } returns true
        every { jwtTokenProvider.getTenantIdFromToken(token) } returns null
        every { jwtTokenProvider.getAuthentication(token) } returns auth

        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val filterChain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(request, response, filterChain)

        verify { filterChain.doFilter(request, response) }
        assertNotNull(SecurityContextHolder.getContext().authentication)
    }
}
