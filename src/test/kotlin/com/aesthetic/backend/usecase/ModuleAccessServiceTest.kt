package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.subscription.*
import com.aesthetic.backend.domain.tenant.SubscriptionPlan
import com.aesthetic.backend.exception.PlanLimitExceededException
import com.aesthetic.backend.repository.SubscriptionModuleRepository
import com.aesthetic.backend.repository.SubscriptionRepository
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

@ExtendWith(MockKExtension::class)
class ModuleAccessServiceTest {

    @MockK
    private lateinit var subscriptionRepository: SubscriptionRepository

    @MockK
    private lateinit var subscriptionModuleRepository: SubscriptionModuleRepository

    private lateinit var moduleAccessService: ModuleAccessService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        moduleAccessService = ModuleAccessService(
            subscriptionRepository,
            subscriptionModuleRepository
        )
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    // --- hasAccess ---

    @Test
    fun `hasAccess should return true for TRIAL status regardless of module`() {
        val subscription = createSubscription(SubscriptionStatus.TRIAL)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        assertTrue(moduleAccessService.hasAccess(tenantId, FeatureModule.BLOG))
        assertTrue(moduleAccessService.hasAccess(tenantId, FeatureModule.GALLERY))
        assertTrue(moduleAccessService.hasAccess(tenantId, FeatureModule.PRODUCTS))
        assertTrue(moduleAccessService.hasAccess(tenantId, FeatureModule.PATIENT_RECORDS))
    }

    @Test
    fun `hasAccess should return true for ACTIVE status when module is active`() {
        val subscription = createSubscription(SubscriptionStatus.ACTIVE)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        val subModule = SubscriptionModule(
            id = "mod-1",
            subscription = subscription,
            module = FeatureModule.BLOG
        )
        every {
            subscriptionModuleRepository.findBySubscriptionAndModuleAndIsActiveTrue(subscription, FeatureModule.BLOG)
        } returns subModule

        assertTrue(moduleAccessService.hasAccess(tenantId, FeatureModule.BLOG))
    }

    @Test
    fun `hasAccess should return false for ACTIVE status when module is not active`() {
        val subscription = createSubscription(SubscriptionStatus.ACTIVE)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription
        every {
            subscriptionModuleRepository.findBySubscriptionAndModuleAndIsActiveTrue(subscription, FeatureModule.PATIENT_RECORDS)
        } returns null

        assertFalse(moduleAccessService.hasAccess(tenantId, FeatureModule.PATIENT_RECORDS))
    }

    @Test
    fun `hasAccess should return true for PAST_DUE status when module is active`() {
        val subscription = createSubscription(SubscriptionStatus.PAST_DUE)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        val subModule = SubscriptionModule(
            id = "mod-1",
            subscription = subscription,
            module = FeatureModule.APPOINTMENTS
        )
        every {
            subscriptionModuleRepository.findBySubscriptionAndModuleAndIsActiveTrue(subscription, FeatureModule.APPOINTMENTS)
        } returns subModule

        assertTrue(moduleAccessService.hasAccess(tenantId, FeatureModule.APPOINTMENTS))
    }

    @Test
    fun `hasAccess should return false for EXPIRED status`() {
        val subscription = createSubscription(SubscriptionStatus.EXPIRED)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        assertFalse(moduleAccessService.hasAccess(tenantId, FeatureModule.BLOG))
    }

    @Test
    fun `hasAccess should return false for CANCELLED status`() {
        val subscription = createSubscription(SubscriptionStatus.CANCELLED)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        assertFalse(moduleAccessService.hasAccess(tenantId, FeatureModule.BLOG))
    }

    @Test
    fun `hasAccess should return false when subscription not found`() {
        every { subscriptionRepository.findByTenantId(tenantId) } returns null

        assertFalse(moduleAccessService.hasAccess(tenantId, FeatureModule.BLOG))
    }

    // --- requireAccess ---

    @Test
    fun `requireAccess should not throw when access is granted`() {
        val subscription = createSubscription(SubscriptionStatus.TRIAL)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        assertDoesNotThrow {
            moduleAccessService.requireAccess(tenantId, FeatureModule.BLOG)
        }
    }

    @Test
    fun `requireAccess should throw PlanLimitExceededException when access is denied`() {
        val subscription = createSubscription(SubscriptionStatus.EXPIRED)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        val exception = assertThrows<PlanLimitExceededException> {
            moduleAccessService.requireAccess(tenantId, FeatureModule.BLOG)
        }

        assertTrue(exception.message!!.contains(FeatureModule.BLOG.name))
    }

    @Test
    fun `requireAccess should throw PlanLimitExceededException when subscription not found`() {
        every { subscriptionRepository.findByTenantId(tenantId) } returns null

        assertThrows<PlanLimitExceededException> {
            moduleAccessService.requireAccess(tenantId, FeatureModule.APPOINTMENTS)
        }
    }

    private fun createSubscription(status: SubscriptionStatus): Subscription {
        return Subscription(
            id = "sub-1",
            plan = SubscriptionPlan.STARTER,
            status = status
        ).apply { tenantId = this@ModuleAccessServiceTest.tenantId }
    }
}
