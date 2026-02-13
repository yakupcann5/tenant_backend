package com.aesthetic.backend.tenant

import com.aesthetic.backend.exception.TenantNotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch

class TenantContextTest {

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `setTenantId and getTenantId should work correctly`() {
        TenantContext.setTenantId("tenant-1")
        assertEquals("tenant-1", TenantContext.getTenantId())
    }

    @Test
    fun `getTenantId should throw when no tenant is set`() {
        assertThrows<TenantNotFoundException> {
            TenantContext.getTenantId()
        }
    }

    @Test
    fun `getTenantIdOrNull should return null when no tenant is set`() {
        assertNull(TenantContext.getTenantIdOrNull())
    }

    @Test
    fun `getTenantIdOrNull should return tenantId when set`() {
        TenantContext.setTenantId("tenant-1")
        assertEquals("tenant-1", TenantContext.getTenantIdOrNull())
    }

    @Test
    fun `clear should remove tenant context`() {
        TenantContext.setTenantId("tenant-1")
        TenantContext.clear()
        assertNull(TenantContext.getTenantIdOrNull())
    }

    @Test
    fun `different threads should have isolated tenant contexts`() {
        TenantContext.setTenantId("tenant-main")

        val latch = CountDownLatch(1)
        var childTenantId: String? = "not-set"

        val thread = Thread {
            childTenantId = TenantContext.getTenantIdOrNull()
            latch.countDown()
        }
        thread.start()
        latch.await()

        assertEquals("tenant-main", TenantContext.getTenantId())
        assertNull(childTenantId)
    }
}
