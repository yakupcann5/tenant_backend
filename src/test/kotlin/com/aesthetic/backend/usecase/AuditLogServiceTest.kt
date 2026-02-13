package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.audit.AuditLog
import com.aesthetic.backend.repository.AuditLogRepository
import com.aesthetic.backend.tenant.TenantContext
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant

@ExtendWith(MockKExtension::class)
class AuditLogServiceTest {

    @MockK
    private lateinit var auditLogRepository: AuditLogRepository

    private lateinit var auditLogService: AuditLogService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        auditLogService = AuditLogService(auditLogRepository)
        TenantContext.setTenantId(tenantId)
        val auth = UsernamePasswordAuthenticationToken("user-1", null, emptyList())
        SecurityContextHolder.getContext().authentication = auth
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `log should save audit log with correct fields`() {
        every { auditLogRepository.save(any()) } answers {
            (firstArg() as AuditLog).apply {
                val idField = AuditLog::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "log-1")
            }
        }

        auditLogService.log("CREATE_APPOINTMENT", "Appointment", "appt-1", """{"status":"PENDING"}""", "192.168.1.1")

        verify {
            auditLogRepository.save(match {
                it.tenantId == tenantId &&
                it.userId == "user-1" &&
                it.action == "CREATE_APPOINTMENT" &&
                it.entityType == "Appointment" &&
                it.entityId == "appt-1" &&
                it.details == """{"status":"PENDING"}""" &&
                it.ipAddress == "192.168.1.1"
            })
        }
    }

    @Test
    fun `log should use system as userId when no authentication present`() {
        SecurityContextHolder.clearContext()

        every { auditLogRepository.save(any()) } answers { firstArg() }

        auditLogService.log("SYSTEM_ACTION", "System", "sys-1")

        verify {
            auditLogRepository.save(match {
                it.userId == "system"
            })
        }
    }

    @Test
    fun `log should not save when TenantContext is null`() {
        TenantContext.clear()

        auditLogService.log("CREATE_APPOINTMENT", "Appointment", "appt-1")

        verify(exactly = 0) { auditLogRepository.save(any()) }
    }

    @Test
    fun `log should not throw when repository throws exception`() {
        every { auditLogRepository.save(any()) } throws RuntimeException("DB error")

        // Should not throw - exception is caught and logged
        assertDoesNotThrow {
            auditLogService.log("CREATE_APPOINTMENT", "Appointment", "appt-1")
        }
    }

    @Test
    fun `listByTenant should return paged audit logs`() {
        val pageable = PageRequest.of(0, 20)
        val auditLog = AuditLog(
            id = "log-1",
            tenantId = tenantId,
            userId = "user-1",
            action = "CREATE_APPOINTMENT",
            entityType = "Appointment",
            entityId = "appt-1",
            details = null,
            ipAddress = "192.168.1.1"
        )
        val page = PageImpl(listOf(auditLog), pageable, 1)

        every { auditLogRepository.findAllByTenantId(tenantId, pageable) } returns page

        val result = auditLogService.listByTenant(pageable)

        assertEquals(1, result.data.size)
        assertEquals("log-1", result.data[0].id)
        assertEquals("CREATE_APPOINTMENT", result.data[0].action)
        assertEquals("Appointment", result.data[0].entityType)
        assertEquals(0, result.page)
        assertEquals(20, result.size)
        assertEquals(1, result.totalElements)
    }

    @Test
    fun `listByUser should return paged audit logs for specific user`() {
        val pageable = PageRequest.of(0, 10)
        val auditLog = AuditLog(
            id = "log-2",
            tenantId = tenantId,
            userId = "user-1",
            action = "UPDATE_SERVICE",
            entityType = "Service",
            entityId = "svc-1"
        )
        val page = PageImpl(listOf(auditLog), pageable, 1)

        every { auditLogRepository.findAllByTenantIdAndUserId(tenantId, "user-1", pageable) } returns page

        val result = auditLogService.listByUser("user-1", pageable)

        assertEquals(1, result.data.size)
        assertEquals("user-1", result.data[0].userId)
        assertEquals("UPDATE_SERVICE", result.data[0].action)
    }

    @Test
    fun `log should save with null details and null ipAddress`() {
        every { auditLogRepository.save(any()) } answers { firstArg() }

        auditLogService.log("DELETE_SERVICE", "Service", "svc-1")

        verify {
            auditLogRepository.save(match {
                it.details == null && it.ipAddress == null
            })
        }
    }
}
