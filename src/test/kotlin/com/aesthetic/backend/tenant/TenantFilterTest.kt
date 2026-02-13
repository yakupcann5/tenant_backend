package com.aesthetic.backend.tenant

import com.aesthetic.backend.domain.tenant.BusinessType
import com.aesthetic.backend.domain.tenant.Tenant
import com.aesthetic.backend.repository.TenantRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

@ExtendWith(MockKExtension::class)
class TenantFilterTest {

    @MockK
    private lateinit var tenantRepository: TenantRepository

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private lateinit var tenantFilter: TenantFilter

    private val testTenant = Tenant(
        id = "tenant-uuid-1",
        slug = "salon1",
        name = "Salon 1",
        businessType = BusinessType.HAIR_SALON,
        isActive = true
    )

    @BeforeEach
    fun setUp() {
        tenantFilter = TenantFilter(tenantRepository, objectMapper)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `should skip filter for auth login endpoint`() {
        val request = MockHttpServletRequest("POST", "/api/auth/login")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        tenantFilter.doFilter(request, response, chain)

        verify(exactly = 0) { tenantRepository.findBySlugAndIsActiveTrue(any()) }
    }

    @Test
    fun `should skip filter for auth refresh endpoint`() {
        val request = MockHttpServletRequest("POST", "/api/auth/refresh")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        tenantFilter.doFilter(request, response, chain)

        verify(exactly = 0) { tenantRepository.findBySlugAndIsActiveTrue(any()) }
    }

    @Test
    fun `should skip filter for platform endpoints`() {
        val request = MockHttpServletRequest("GET", "/api/platform/tenants")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        tenantFilter.doFilter(request, response, chain)

        verify(exactly = 0) { tenantRepository.findBySlugAndIsActiveTrue(any()) }
    }

    @Test
    fun `should skip filter for actuator endpoints`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        tenantFilter.doFilter(request, response, chain)

        verify(exactly = 0) { tenantRepository.findBySlugAndIsActiveTrue(any()) }
    }

    @Test
    fun `should skip filter for swagger endpoints`() {
        val request = MockHttpServletRequest("GET", "/swagger-ui/index.html")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        tenantFilter.doFilter(request, response, chain)

        verify(exactly = 0) { tenantRepository.findBySlugAndIsActiveTrue(any()) }
    }

    @Test
    fun `should skip filter for webhook endpoints`() {
        val request = MockHttpServletRequest("POST", "/api/webhooks/iyzico")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        tenantFilter.doFilter(request, response, chain)

        verify(exactly = 0) { tenantRepository.findBySlugAndIsActiveTrue(any()) }
    }

    @Test
    fun `should resolve tenant from subdomain`() {
        every { tenantRepository.findBySlugAndIsActiveTrue("salon1") } returns testTenant

        val request = MockHttpServletRequest("GET", "/api/admin/services").apply {
            serverName = "salon1.app.com"
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        tenantFilter.doFilter(request, response, chain)

        verify { tenantRepository.findBySlugAndIsActiveTrue("salon1") }
        assertNull(TenantContext.getTenantIdOrNull())
    }

    @Test
    fun `should resolve tenant from custom domain`() {
        every { tenantRepository.findByCustomDomainAndIsActiveTrue("salon.com") } returns testTenant

        val request = MockHttpServletRequest("GET", "/api/public/services").apply {
            serverName = "salon.com"
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        tenantFilter.doFilter(request, response, chain)

        verify { tenantRepository.findByCustomDomainAndIsActiveTrue("salon.com") }
    }

    @Test
    fun `should use cache for repeated tenant lookups`() {
        every { tenantRepository.findBySlugAndIsActiveTrue("salon1") } returns testTenant

        val request = MockHttpServletRequest("GET", "/api/admin/services").apply {
            serverName = "salon1.app.com"
        }

        tenantFilter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
        tenantFilter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        verify(exactly = 1) { tenantRepository.findBySlugAndIsActiveTrue("salon1") }
    }

    @Test
    fun `should skip www api admin subdomains and fall through to custom domain`() {
        every { tenantRepository.findByCustomDomainAndIsActiveTrue("www.app.com") } returns testTenant

        val request = MockHttpServletRequest("GET", "/api/public/services").apply {
            serverName = "www.app.com"
        }

        tenantFilter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        verify(exactly = 0) { tenantRepository.findBySlugAndIsActiveTrue(any()) }
        verify { tenantRepository.findByCustomDomainAndIsActiveTrue("www.app.com") }
    }

    @Test
    fun `should clear context after filter completes`() {
        every { tenantRepository.findBySlugAndIsActiveTrue("salon1") } returns testTenant

        val request = MockHttpServletRequest("GET", "/api/admin/services").apply {
            serverName = "salon1.app.com"
        }

        tenantFilter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertNull(TenantContext.getTenantIdOrNull())
    }

    @Test
    fun `should return 404 JSON error when tenant not found`() {
        every { tenantRepository.findBySlugAndIsActiveTrue("unknown") } returns null

        val request = MockHttpServletRequest("GET", "/api/admin/services").apply {
            serverName = "unknown.app.com"
        }
        val response = MockHttpServletResponse()

        tenantFilter.doFilter(request, response, MockFilterChain())

        assertEquals(404, response.status)
        assertTrue(response.contentType!!.startsWith("application/json"))
        val body = objectMapper.readTree(response.contentAsString)
        assertEquals(false, body["success"].asBoolean())
        assertEquals("TENANT_NOT_FOUND", body["code"].asText())
        assertNull(TenantContext.getTenantIdOrNull())
    }
}
