package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.UpdateClientStatusRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.tenant.TenantContext
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

@ExtendWith(MockKExtension::class)
class ClientManagementServiceTest {

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var appointmentRepository: AppointmentRepository

    private lateinit var clientManagementService: ClientManagementService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        clientManagementService = ClientManagementService(userRepository, appointmentRepository)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `listClients should return paged response with appointment counts`() {
        val pageable = PageRequest.of(0, 20)
        val client = createClientUser()
        val page = PageImpl(listOf(client), pageable, 1)
        every { userRepository.findAllByTenantIdAndRole(tenantId, Role.CLIENT, pageable) } returns page
        every { appointmentRepository.countByTenantIdAndClientId(tenantId, "client-1") } returns 5L

        val result = clientManagementService.listClients(pageable)

        assertEquals(1, result.data.size)
        assertEquals("client-1", result.data[0].id)
        assertEquals("Mehmet", result.data[0].firstName)
        assertEquals("Demir", result.data[0].lastName)
        assertEquals(5L, result.data[0].appointmentCount)
        assertEquals(0, result.page)
        assertEquals(20, result.size)
        assertEquals(1, result.totalElements)
    }

    @Test
    fun `getClientDetail should return detail with aggregate data`() {
        val client = createClientUser()
        every { userRepository.findById("client-1") } returns Optional.of(client)
        every { appointmentRepository.countByTenantIdAndClientId(tenantId, "client-1") } returns 10L
        every { appointmentRepository.findLastAppointmentDateByClientId(tenantId, "client-1") } returns LocalDate.of(2025, 1, 15)
        every { appointmentRepository.sumTotalSpentByClientId(tenantId, "client-1") } returns BigDecimal("1500.00")

        val result = clientManagementService.getClientDetail("client-1")

        assertEquals("client-1", result.id)
        assertEquals("Mehmet", result.firstName)
        assertEquals("Demir", result.lastName)
        assertEquals("mehmet@example.com", result.email)
        assertEquals("05551234567", result.phone)
        assertFalse(result.isBlacklisted)
        assertEquals(10L, result.appointmentCount)
        assertEquals(LocalDate.of(2025, 1, 15), result.lastAppointmentDate)
        assertEquals(BigDecimal("1500.00"), result.totalSpent)
        assertEquals(0, result.noShowCount)
    }

    @Test
    fun `getClientDetail should throw when not found`() {
        every { userRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            clientManagementService.getClientDetail("nonexistent")
        }
    }

    @Test
    fun `updateClientStatus should blacklist client`() {
        val client = createClientUser()
        every { userRepository.findById("client-1") } returns Optional.of(client)
        every { userRepository.save(any()) } answers { firstArg() }
        every { appointmentRepository.countByTenantIdAndClientId(tenantId, "client-1") } returns 5L
        every { appointmentRepository.findLastAppointmentDateByClientId(tenantId, "client-1") } returns null
        every { appointmentRepository.sumTotalSpentByClientId(tenantId, "client-1") } returns BigDecimal.ZERO

        val request = UpdateClientStatusRequest(
            isBlacklisted = true,
            blacklistReason = "No-show tekrari"
        )
        val result = clientManagementService.updateClientStatus("client-1", request)

        assertTrue(result.isBlacklisted)
        assertEquals("No-show tekrari", result.blacklistReason)
    }

    @Test
    fun `updateClientStatus should remove from blacklist`() {
        val client = createClientUser().apply {
            isBlacklisted = true
            blacklistedAt = java.time.Instant.now()
            blacklistReason = "No-show tekrari"
        }
        every { userRepository.findById("client-1") } returns Optional.of(client)
        every { userRepository.save(any()) } answers { firstArg() }
        every { appointmentRepository.countByTenantIdAndClientId(tenantId, "client-1") } returns 5L
        every { appointmentRepository.findLastAppointmentDateByClientId(tenantId, "client-1") } returns null
        every { appointmentRepository.sumTotalSpentByClientId(tenantId, "client-1") } returns BigDecimal.ZERO

        val request = UpdateClientStatusRequest(isBlacklisted = false)
        val result = clientManagementService.updateClientStatus("client-1", request)

        assertFalse(result.isBlacklisted)
        assertNull(result.blacklistReason)
    }

    @Test
    fun `updateClientStatus should throw when not found`() {
        every { userRepository.findById("nonexistent") } returns Optional.empty()

        val request = UpdateClientStatusRequest(isBlacklisted = true)

        assertThrows<ResourceNotFoundException> {
            clientManagementService.updateClientStatus("nonexistent", request)
        }
    }

    @Test
    fun `removeFromBlacklist should clear blacklist fields`() {
        val client = createClientUser().apply {
            isBlacklisted = true
            blacklistedAt = java.time.Instant.now()
            blacklistReason = "No-show tekrari"
        }
        every { userRepository.findById("client-1") } returns Optional.of(client)
        every { userRepository.save(any()) } answers { firstArg() }
        every { appointmentRepository.countByTenantIdAndClientId(tenantId, "client-1") } returns 5L
        every { appointmentRepository.findLastAppointmentDateByClientId(tenantId, "client-1") } returns null
        every { appointmentRepository.sumTotalSpentByClientId(tenantId, "client-1") } returns BigDecimal.ZERO

        val result = clientManagementService.removeFromBlacklist("client-1")

        assertFalse(result.isBlacklisted)
        assertNull(result.blacklistReason)
    }

    @Test
    fun `removeFromBlacklist should throw when not found`() {
        every { userRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            clientManagementService.removeFromBlacklist("nonexistent")
        }
    }

    private fun createClientUser(id: String = "client-1") = User(
        id = id,
        firstName = "Mehmet",
        lastName = "Demir",
        email = "mehmet@example.com",
        passwordHash = "hashed-password",
        phone = "05551234567",
        role = Role.CLIENT
    ).apply { tenantId = this@ClientManagementServiceTest.tenantId }
}
