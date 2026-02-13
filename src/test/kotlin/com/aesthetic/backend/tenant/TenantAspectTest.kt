package com.aesthetic.backend.tenant

import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManager
import org.hibernate.Filter
import org.hibernate.Session
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class TenantAspectTest {

    @MockK
    private lateinit var entityManager: EntityManager

    @MockK
    private lateinit var session: Session

    @MockK
    private lateinit var filter: Filter

    private lateinit var tenantAspect: TenantAspect

    @BeforeEach
    fun setUp() {
        tenantAspect = TenantAspect(entityManager)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `should enable tenant filter when tenant context is set`() {
        TenantContext.setTenantId("tenant-1")
        every { entityManager.unwrap(Session::class.java) } returns session
        every { session.enableFilter("tenantFilter") } returns filter
        every { filter.setParameter("tenantId", "tenant-1") } returns filter

        tenantAspect.enableTenantFilter()

        verify { session.enableFilter("tenantFilter") }
        verify { filter.setParameter("tenantId", "tenant-1") }
    }

    @Test
    fun `should skip filter when no tenant context (platform admin)`() {
        tenantAspect.enableTenantFilter()

        verify(exactly = 0) { entityManager.unwrap(any<Class<*>>()) }
    }
}
