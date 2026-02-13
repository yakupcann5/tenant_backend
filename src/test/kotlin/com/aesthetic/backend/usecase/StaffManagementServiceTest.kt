package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.CreateStaffRequest
import com.aesthetic.backend.dto.request.UpdateStaffRequest
import com.aesthetic.backend.exception.PlanLimitExceededException
import com.aesthetic.backend.exception.ResourceNotFoundException
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.*

@ExtendWith(MockKExtension::class)
class StaffManagementServiceTest {

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var planLimitService: PlanLimitService

    @MockK
    private lateinit var passwordEncoder: BCryptPasswordEncoder

    private lateinit var staffManagementService: StaffManagementService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        staffManagementService = StaffManagementService(userRepository, planLimitService, passwordEncoder)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `createStaff should save new staff with plan limit check`() {
        every { planLimitService.checkCanCreateStaff(tenantId) } just Runs
        every { userRepository.findByEmailAndTenantId("staff@example.com", tenantId) } returns null
        every { passwordEncoder.encode("SecurePass1") } returns "hashed-password"
        every { userRepository.save(any()) } answers {
            (firstArg() as User).apply {
                val idField = User::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "user-1")
            }
        }

        val request = CreateStaffRequest(
            firstName = "Ayse",
            lastName = "Yilmaz",
            email = "staff@example.com",
            password = "SecurePass1",
            phone = "05551234567",
            title = "Uzman"
        )
        val result = staffManagementService.createStaff(request)

        assertEquals("user-1", result.id)
        assertEquals("Ayse", result.firstName)
        assertEquals("Yilmaz", result.lastName)
        assertEquals("staff@example.com", result.email)
        assertEquals("05551234567", result.phone)
        assertEquals("Uzman", result.title)
        assertTrue(result.isActive)

        verify { planLimitService.checkCanCreateStaff(tenantId) }
        verify { passwordEncoder.encode("SecurePass1") }
    }

    @Test
    fun `createStaff should throw when plan limit exceeded`() {
        every { planLimitService.checkCanCreateStaff(tenantId) } throws PlanLimitExceededException("Personel limiti doldu")

        val request = CreateStaffRequest(
            firstName = "Ayse",
            lastName = "Yilmaz",
            email = "staff@example.com",
            password = "SecurePass1"
        )

        assertThrows<PlanLimitExceededException> {
            staffManagementService.createStaff(request)
        }
    }

    @Test
    fun `createStaff should throw when email already exists`() {
        every { planLimitService.checkCanCreateStaff(tenantId) } just Runs
        every { userRepository.findByEmailAndTenantId("existing@example.com", tenantId) } returns createStaffUser()

        val request = CreateStaffRequest(
            firstName = "Ayse",
            lastName = "Yilmaz",
            email = "existing@example.com",
            password = "SecurePass1"
        )

        assertThrows<IllegalArgumentException> {
            staffManagementService.createStaff(request)
        }
    }

    @Test
    fun `updateStaff should update provided fields`() {
        val user = createStaffUser()
        every { userRepository.findById("user-1") } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        val request = UpdateStaffRequest(
            firstName = "Updated Name",
            lastName = "Updated Last",
            phone = "05559999999",
            title = "Senior Uzman",
            image = "https://example.com/avatar.jpg"
        )
        val result = staffManagementService.updateStaff("user-1", request)

        assertEquals("Updated Name", result.firstName)
        assertEquals("Updated Last", result.lastName)
        assertEquals("05559999999", result.phone)
        assertEquals("Senior Uzman", result.title)
        assertEquals("https://example.com/avatar.jpg", result.image)
    }

    @Test
    fun `updateStaff should throw when not found`() {
        every { userRepository.findById("nonexistent") } returns Optional.empty()

        val request = UpdateStaffRequest(firstName = "Updated")

        assertThrows<ResourceNotFoundException> {
            staffManagementService.updateStaff("nonexistent", request)
        }
    }

    @Test
    fun `updateStaff should throw when user is not staff`() {
        val adminUser = createStaffUser().apply { role = Role.TENANT_ADMIN }
        every { userRepository.findById("user-1") } returns Optional.of(adminUser)

        val request = UpdateStaffRequest(firstName = "Updated")

        assertThrows<ResourceNotFoundException> {
            staffManagementService.updateStaff("user-1", request)
        }
    }

    @Test
    fun `activateStaff should set isActive to true`() {
        val user = createStaffUser().apply { isActive = false }
        every { userRepository.findById("user-1") } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        val result = staffManagementService.activateStaff("user-1")

        assertTrue(result.isActive)
    }

    @Test
    fun `deactivateStaff should set isActive to false`() {
        val user = createStaffUser()
        every { userRepository.findById("user-1") } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        val result = staffManagementService.deactivateStaff("user-1")

        assertFalse(result.isActive)
    }

    @Test
    fun `deactivateStaff should throw when not found`() {
        every { userRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            staffManagementService.deactivateStaff("nonexistent")
        }
    }

    @Test
    fun `listStaff should return list of staff`() {
        val staffList = listOf(createStaffUser())
        every { userRepository.findByTenantIdAndRoleIn(tenantId, listOf(Role.STAFF)) } returns staffList

        val result = staffManagementService.listStaff()

        assertEquals(1, result.size)
        assertEquals("user-1", result[0].id)
        assertEquals("Ayse", result[0].firstName)
    }

    private fun createStaffUser(id: String = "user-1") = User(
        id = id,
        firstName = "Ayse",
        lastName = "Yilmaz",
        email = "staff@example.com",
        passwordHash = "hashed-password",
        phone = "05551234567",
        title = "Uzman",
        role = Role.STAFF
    ).apply { tenantId = this@StaffManagementServiceTest.tenantId }
}
