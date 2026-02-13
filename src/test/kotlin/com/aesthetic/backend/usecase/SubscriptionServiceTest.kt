package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.subscription.*
import com.aesthetic.backend.domain.tenant.SubscriptionPlan
import com.aesthetic.backend.domain.tenant.Tenant
import com.aesthetic.backend.domain.tenant.BusinessType
import com.aesthetic.backend.dto.request.ChangePlanRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.SubscriptionModuleRepository
import com.aesthetic.backend.repository.SubscriptionRepository
import com.aesthetic.backend.repository.TenantRepository
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
import java.time.LocalDate
import java.util.*

@ExtendWith(MockKExtension::class)
class SubscriptionServiceTest {

    @MockK
    private lateinit var subscriptionRepository: SubscriptionRepository

    @MockK
    private lateinit var subscriptionModuleRepository: SubscriptionModuleRepository

    @MockK
    private lateinit var tenantRepository: TenantRepository

    private lateinit var subscriptionService: SubscriptionService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        subscriptionService = SubscriptionService(
            subscriptionRepository,
            subscriptionModuleRepository,
            tenantRepository
        )
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `createTrialSubscription should create subscription with TRIAL plan and 14 day trial`() {
        every { subscriptionRepository.save(any()) } answers {
            (firstArg() as Subscription).apply {
                val idField = Subscription::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "sub-1")
            }
        }

        val result = subscriptionService.createTrialSubscription(tenantId)

        assertEquals(SubscriptionPlan.TRIAL, result.plan)
        assertEquals(SubscriptionStatus.TRIAL, result.status)
        assertEquals(LocalDate.now().plusDays(14), result.trialEndDate)
        assertEquals(tenantId, result.tenantId)
        verify { subscriptionRepository.save(any()) }
    }

    @Test
    fun `changePlan should upgrade immediately when new plan is higher`() {
        val subscription = createSubscription(SubscriptionPlan.STARTER, SubscriptionStatus.ACTIVE)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription
        every { subscriptionModuleRepository.deleteAllBySubscription(subscription) } just Runs
        every { subscriptionModuleRepository.save(any()) } answers { firstArg() }
        every { subscriptionRepository.save(any()) } answers { firstArg() }
        every { subscriptionModuleRepository.findAllBySubscriptionAndIsActiveTrue(subscription) } returns emptyList()

        val tenant = createTenant()
        every { tenantRepository.findById(tenantId) } returns Optional.of(tenant)
        every { tenantRepository.save(any()) } answers { firstArg() }

        val request = ChangePlanRequest(
            plan = SubscriptionPlan.PROFESSIONAL,
            billingPeriod = BillingPeriod.MONTHLY
        )
        val result = subscriptionService.changePlan(request)

        assertEquals(SubscriptionPlan.PROFESSIONAL, result.plan)
        assertNull(result.pendingPlanChange)
    }

    @Test
    fun `changePlan should schedule downgrade for next billing cycle`() {
        val subscription = createSubscription(SubscriptionPlan.PROFESSIONAL, SubscriptionStatus.ACTIVE)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription
        every { subscriptionRepository.save(any()) } answers { firstArg() }
        every { subscriptionModuleRepository.findAllBySubscriptionAndIsActiveTrue(subscription) } returns emptyList()

        val request = ChangePlanRequest(
            plan = SubscriptionPlan.STARTER,
            billingPeriod = BillingPeriod.MONTHLY
        )
        val result = subscriptionService.changePlan(request)

        assertEquals(SubscriptionPlan.PROFESSIONAL, result.plan)
        assertEquals(SubscriptionPlan.STARTER, result.pendingPlanChange)
    }

    @Test
    fun `changePlan should throw when switching to TRIAL`() {
        val subscription = createSubscription(SubscriptionPlan.STARTER, SubscriptionStatus.ACTIVE)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        val request = ChangePlanRequest(
            plan = SubscriptionPlan.TRIAL,
            billingPeriod = BillingPeriod.MONTHLY
        )

        assertThrows<IllegalArgumentException> {
            subscriptionService.changePlan(request)
        }
    }

    @Test
    fun `changePlan should throw when same plan`() {
        val subscription = createSubscription(SubscriptionPlan.STARTER, SubscriptionStatus.ACTIVE)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        val request = ChangePlanRequest(
            plan = SubscriptionPlan.STARTER,
            billingPeriod = BillingPeriod.MONTHLY
        )

        assertThrows<IllegalArgumentException> {
            subscriptionService.changePlan(request)
        }
    }

    @Test
    fun `changePlan should throw when subscription not found`() {
        every { subscriptionRepository.findByTenantId(tenantId) } returns null

        val request = ChangePlanRequest(
            plan = SubscriptionPlan.PROFESSIONAL,
            billingPeriod = BillingPeriod.MONTHLY
        )

        assertThrows<ResourceNotFoundException> {
            subscriptionService.changePlan(request)
        }
    }

    @Test
    fun `cancelSubscription should set status to CANCELLED when ACTIVE`() {
        val subscription = createSubscription(SubscriptionPlan.STARTER, SubscriptionStatus.ACTIVE)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription
        every { subscriptionRepository.save(any()) } answers { firstArg() }
        every { subscriptionModuleRepository.findAllBySubscriptionAndIsActiveTrue(subscription) } returns emptyList()

        val result = subscriptionService.cancelSubscription()

        assertEquals(SubscriptionStatus.CANCELLED, result.status)
        verify { subscriptionRepository.save(any()) }
    }

    @Test
    fun `cancelSubscription should set status to CANCELLED when TRIAL`() {
        val subscription = createSubscription(SubscriptionPlan.TRIAL, SubscriptionStatus.TRIAL)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription
        every { subscriptionRepository.save(any()) } answers { firstArg() }
        every { subscriptionModuleRepository.findAllBySubscriptionAndIsActiveTrue(subscription) } returns emptyList()

        val result = subscriptionService.cancelSubscription()

        assertEquals(SubscriptionStatus.CANCELLED, result.status)
    }

    @Test
    fun `cancelSubscription should throw when subscription is EXPIRED`() {
        val subscription = createSubscription(SubscriptionPlan.STARTER, SubscriptionStatus.EXPIRED)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        assertThrows<IllegalArgumentException> {
            subscriptionService.cancelSubscription()
        }
    }

    @Test
    fun `cancelSubscription should throw when subscription is already CANCELLED`() {
        val subscription = createSubscription(SubscriptionPlan.STARTER, SubscriptionStatus.CANCELLED)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        assertThrows<IllegalArgumentException> {
            subscriptionService.cancelSubscription()
        }
    }

    @Test
    fun `cancelSubscription should throw when subscription not found`() {
        every { subscriptionRepository.findByTenantId(tenantId) } returns null

        assertThrows<ResourceNotFoundException> {
            subscriptionService.cancelSubscription()
        }
    }

    @Test
    fun `getSubscription should return subscription with modules`() {
        val subscription = createSubscription(SubscriptionPlan.STARTER, SubscriptionStatus.ACTIVE)
        every { subscriptionRepository.findByTenantId(tenantId) } returns subscription

        val subModule = SubscriptionModule(
            id = "mod-1",
            subscription = subscription,
            module = FeatureModule.APPOINTMENTS
        ).apply { this.tenantId = this@SubscriptionServiceTest.tenantId }
        every { subscriptionModuleRepository.findAllBySubscriptionAndIsActiveTrue(subscription) } returns listOf(subModule)

        val result = subscriptionService.getSubscription()

        assertEquals(SubscriptionPlan.STARTER, result.plan)
        assertEquals(SubscriptionStatus.ACTIVE, result.status)
        assertEquals(listOf(FeatureModule.APPOINTMENTS), result.enabledModules)
    }

    @Test
    fun `getSubscription should throw when not found`() {
        every { subscriptionRepository.findByTenantId(tenantId) } returns null

        assertThrows<ResourceNotFoundException> {
            subscriptionService.getSubscription()
        }
    }

    @Test
    fun `getActiveModules should return all modules for TRIAL status`() {
        val subscription = createSubscription(SubscriptionPlan.TRIAL, SubscriptionStatus.TRIAL)

        val result = subscriptionService.getActiveModules(subscription)

        assertEquals(FeatureModule.entries.toList(), result)
    }

    @Test
    fun `getActiveModules should return only active modules for ACTIVE status`() {
        val subscription = createSubscription(SubscriptionPlan.STARTER, SubscriptionStatus.ACTIVE)
        val subModule = SubscriptionModule(
            id = "mod-1",
            subscription = subscription,
            module = FeatureModule.BLOG
        )
        every { subscriptionModuleRepository.findAllBySubscriptionAndIsActiveTrue(subscription) } returns listOf(subModule)

        val result = subscriptionService.getActiveModules(subscription)

        assertEquals(listOf(FeatureModule.BLOG), result)
    }

    private fun createSubscription(
        plan: SubscriptionPlan = SubscriptionPlan.TRIAL,
        status: SubscriptionStatus = SubscriptionStatus.TRIAL
    ): Subscription {
        return Subscription(
            id = "sub-1",
            plan = plan,
            status = status,
            trialEndDate = if (status == SubscriptionStatus.TRIAL) LocalDate.now().plusDays(14) else null
        ).apply { tenantId = this@SubscriptionServiceTest.tenantId }
    }

    private fun createTenant(): Tenant {
        return Tenant(
            id = tenantId,
            slug = "test-salon",
            name = "Test Salon",
            businessType = BusinessType.HAIR_SALON
        )
    }
}
