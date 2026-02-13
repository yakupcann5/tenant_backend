package com.aesthetic.backend.tenant

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TenantEntityListenerTest {

    private val listener = TenantEntityListener()

    @BeforeEach
    fun setUp() {
        TenantContext.setTenantId("tenant-1")
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `onPrePersist should auto-set tenantId when not initialized`() {
        val entity = TestTenantEntity()
        listener.onPrePersist(entity)
        assertEquals("tenant-1", entity.tenantId)
    }

    @Test
    fun `onPrePersist should allow same tenantId`() {
        val entity = TestTenantEntity()
        entity.tenantId = "tenant-1"
        listener.onPrePersist(entity)
        assertEquals("tenant-1", entity.tenantId)
    }

    @Test
    fun `onPrePersist should throw SecurityException for cross-tenant write`() {
        val entity = TestTenantEntity()
        entity.tenantId = "tenant-2"
        assertThrows<SecurityException> {
            listener.onPrePersist(entity)
        }
    }

    @Test
    fun `onPrePersist should skip when no tenant context (platform admin)`() {
        TenantContext.clear()
        val entity = TestTenantEntity()
        listener.onPrePersist(entity) // should not throw
    }

    @Test
    fun `onPreUpdate should allow same tenantId`() {
        val entity = TestTenantEntity()
        entity.tenantId = "tenant-1"
        listener.onPreUpdate(entity)
    }

    @Test
    fun `onPreUpdate should throw SecurityException for cross-tenant update`() {
        val entity = TestTenantEntity()
        entity.tenantId = "tenant-2"
        assertThrows<SecurityException> {
            listener.onPreUpdate(entity)
        }
    }

    @Test
    fun `onPreUpdate should skip when no tenant context (platform admin)`() {
        TenantContext.clear()
        val entity = TestTenantEntity()
        entity.tenantId = "tenant-2"
        listener.onPreUpdate(entity) // should not throw
    }

    @Test
    fun `onPreRemove should allow same tenantId`() {
        val entity = TestTenantEntity()
        entity.tenantId = "tenant-1"
        listener.onPreRemove(entity)
    }

    @Test
    fun `onPreRemove should throw SecurityException for cross-tenant delete`() {
        val entity = TestTenantEntity()
        entity.tenantId = "tenant-2"
        assertThrows<SecurityException> {
            listener.onPreRemove(entity)
        }
    }

    @Test
    fun `onPreRemove should skip when no tenant context (platform admin)`() {
        TenantContext.clear()
        val entity = TestTenantEntity()
        entity.tenantId = "tenant-2"
        listener.onPreRemove(entity) // should not throw
    }

    @Test
    fun `should ignore non-TenantAwareEntity objects`() {
        val entity = object {}
        listener.onPrePersist(entity)
        listener.onPreUpdate(entity)
        listener.onPreRemove(entity)
    }

    private class TestTenantEntity : TenantAwareEntity()
}
