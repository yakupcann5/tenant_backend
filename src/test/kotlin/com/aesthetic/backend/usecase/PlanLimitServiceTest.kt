package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.subscription.Subscription
import com.aesthetic.backend.domain.subscription.SubscriptionStatus
import com.aesthetic.backend.domain.tenant.SubscriptionPlan
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.exception.PlanLimitExceededException
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.repository.SubscriptionRepository
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
import java.time.Instant

@ExtendWith(MockKExtension::class)
class PlanLimitServiceTest {

    @MockK
    private lateinit var subscriptionRepository: SubscriptionRepository

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var appointmentRepository: AppointmentRepository

    private lateinit var planLimitService: PlanLimitService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        planLimitService = PlanLimitService(
            subscriptionRepository,
            userRepository,
            appointmentRepository
        )
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    // --- checkCanCreateAppointment ---

    @Test
    fun `checkCanCreateAppointment should pass when under limit`() {
        val subscription = createSubscription(SubscriptionPlan.STARTER)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription
        every { appointmentRepository.countByTenantIdAndCreatedAtAfter(tenantId, any<Instant>()) } returns 50L

        assertDoesNotThrow {
            planLimitService.checkCanCreateAppointment(tenantId)
        }
    }

    @Test
    fun `checkCanCreateAppointment should throw when over limit`() {
        val subscription = createSubscription(SubscriptionPlan.STARTER)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription
        every { appointmentRepository.countByTenantIdAndCreatedAtAfter(tenantId, any<Instant>()) } returns 100L

        assertThrows<PlanLimitExceededException> {
            planLimitService.checkCanCreateAppointment(tenantId)
        }
    }

    @Test
    fun `checkCanCreateAppointment should pass for ENTERPRISE plan regardless of count`() {
        val subscription = createSubscription(SubscriptionPlan.ENTERPRISE)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        assertDoesNotThrow {
            planLimitService.checkCanCreateAppointment(tenantId)
        }

        verify(exactly = 0) { appointmentRepository.countByTenantIdAndCreatedAtAfter(any(), any()) }
    }

    @Test
    fun `checkCanCreateAppointment should throw when subscription not found`() {
        every { subscriptionRepository.findByTenantId(tenantId) } returns null

        assertThrows<ResourceNotFoundException> {
            planLimitService.checkCanCreateAppointment(tenantId)
        }
    }

    // --- checkCanCreateStaff ---

    @Test
    fun `checkCanCreateStaff should pass when under limit`() {
        val subscription = createSubscription(SubscriptionPlan.PROFESSIONAL)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription
        every { userRepository.countByTenantIdAndRole(tenantId, Role.STAFF) } returns 3L

        assertDoesNotThrow {
            planLimitService.checkCanCreateStaff(tenantId)
        }
    }

    @Test
    fun `checkCanCreateStaff should throw when over limit`() {
        val subscription = createSubscription(SubscriptionPlan.STARTER)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription
        every { userRepository.countByTenantIdAndRole(tenantId, Role.STAFF) } returns 1L

        assertThrows<PlanLimitExceededException> {
            planLimitService.checkCanCreateStaff(tenantId)
        }
    }

    @Test
    fun `checkCanCreateStaff should pass for ENTERPRISE plan regardless of count`() {
        val subscription = createSubscription(SubscriptionPlan.ENTERPRISE)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        assertDoesNotThrow {
            planLimitService.checkCanCreateStaff(tenantId)
        }

        verify(exactly = 0) { userRepository.countByTenantIdAndRole(any(), any()) }
    }

    @Test
    fun `checkCanCreateStaff should throw when subscription not found`() {
        every { subscriptionRepository.findByTenantId(tenantId) } returns null

        assertThrows<ResourceNotFoundException> {
            planLimitService.checkCanCreateStaff(tenantId)
        }
    }

    // --- getCurrentUsage ---

    @Test
    fun `getCurrentUsage should return correct usage data`() {
        val subscription = createSubscription(SubscriptionPlan.PROFESSIONAL)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription
        every { userRepository.countByTenantIdAndRole(tenantId, Role.STAFF) } returns 3L
        every { appointmentRepository.countByTenantIdAndCreatedAtAfter(tenantId, any<Instant>()) } returns 120L

        val result = planLimitService.getCurrentUsage(tenantId)

        assertEquals(3L, result.staffCount)
        assertEquals(5, result.maxStaff)
        assertEquals(120L, result.appointmentCount)
        assertEquals(500, result.maxAppointments)
        assertEquals(2048, result.maxStorageMB)
    }

    @Test
    fun `getCurrentUsage should throw when subscription not found`() {
        every { subscriptionRepository.findByTenantId(tenantId) } returns null

        assertThrows<ResourceNotFoundException> {
            planLimitService.getCurrentUsage(tenantId)
        }
    }

    private fun createSubscription(plan: SubscriptionPlan): Subscription {
        return Subscription(
            id = "sub-1",
            plan = plan,
            status = SubscriptionStatus.ACTIVE
        ).apply { tenantId = this@PlanLimitServiceTest.tenantId }
    }
}
