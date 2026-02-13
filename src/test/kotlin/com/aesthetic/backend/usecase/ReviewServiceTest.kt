package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.appointment.Appointment
import com.aesthetic.backend.domain.review.Review
import com.aesthetic.backend.domain.review.ReviewApprovalStatus
import com.aesthetic.backend.domain.service.Service
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.AdminRespondReviewRequest
import com.aesthetic.backend.dto.request.CreateReviewRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.ReviewRepository
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.tenant.TenantContext
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.util.*

@ExtendWith(MockKExtension::class)
class ReviewServiceTest {

    @MockK
    private lateinit var reviewRepository: ReviewRepository

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var entityManager: EntityManager

    private lateinit var reviewService: ReviewService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        reviewService = ReviewService(reviewRepository, userRepository, entityManager)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `createByClient should save review with client name`() {
        val client = createUser("user-1", "Ali", "Veli")
        val principal = createPrincipal("user-1")
        every { userRepository.findById("user-1") } returns Optional.of(client)
        every { reviewRepository.save(any()) } answers {
            (firstArg() as Review).apply {
                val idField = Review::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "rev-1")
            }
        }

        val request = CreateReviewRequest(rating = 5, comment = "Harika hizmet")
        val result = reviewService.createByClient(request, principal)

        assertEquals("rev-1", result.id)
        assertEquals(5, result.rating)
        assertEquals("Harika hizmet", result.comment)
        assertEquals("Ali Veli", result.clientName)
        assertEquals(ReviewApprovalStatus.PENDING, result.approvalStatus)
        assertNull(result.appointmentId)
        assertNull(result.serviceId)
        assertNull(result.serviceName)
    }

    @Test
    fun `createByClient with appointmentId and serviceId should use getReference`() {
        val client = createUser("user-1", "Ali", "Veli")
        val principal = createPrincipal("user-1")
        val appointmentProxy = mockk<Appointment> { every { id } returns "appt-1" }
        val serviceProxy = mockk<Service> { every { id } returns "svc-1"; every { title } returns "Botoks" }

        every { userRepository.findById("user-1") } returns Optional.of(client)
        every { entityManager.getReference(Appointment::class.java, "appt-1") } returns appointmentProxy
        every { entityManager.getReference(Service::class.java, "svc-1") } returns serviceProxy
        every { reviewRepository.save(any()) } answers {
            (firstArg() as Review).apply {
                val idField = Review::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "rev-2")
            }
        }

        val request = CreateReviewRequest(rating = 4, comment = "Good", appointmentId = "appt-1", serviceId = "svc-1")
        val result = reviewService.createByClient(request, principal)

        assertEquals("appt-1", result.appointmentId)
        assertEquals("svc-1", result.serviceId)
        assertEquals("Botoks", result.serviceName)
        verify { entityManager.getReference(Appointment::class.java, "appt-1") }
        verify { entityManager.getReference(Service::class.java, "svc-1") }
    }

    @Test
    fun `createByClient should throw when user not found`() {
        val principal = createPrincipal("nonexistent")
        every { userRepository.findById("nonexistent") } returns Optional.empty()

        val request = CreateReviewRequest(rating = 4, comment = "Good")

        assertThrows<ResourceNotFoundException> {
            reviewService.createByClient(request, principal)
        }
    }

    @Test
    fun `approve should set approval status to APPROVED`() {
        val review = createReview("rev-1")
        every { reviewRepository.findById("rev-1") } returns Optional.of(review)
        every { reviewRepository.save(any()) } answers { firstArg() }

        val result = reviewService.approve("rev-1")

        assertEquals(ReviewApprovalStatus.APPROVED, result.approvalStatus)
        verify { reviewRepository.save(any()) }
    }

    @Test
    fun `approve should throw when review not found`() {
        every { reviewRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            reviewService.approve("nonexistent")
        }
    }

    @Test
    fun `reject should set approval status to REJECTED`() {
        val review = createReview("rev-1")
        every { reviewRepository.findById("rev-1") } returns Optional.of(review)
        every { reviewRepository.save(any()) } answers { firstArg() }

        val result = reviewService.reject("rev-1")

        assertEquals(ReviewApprovalStatus.REJECTED, result.approvalStatus)
        verify { reviewRepository.save(any()) }
    }

    @Test
    fun `reject should throw when review not found`() {
        every { reviewRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            reviewService.reject("nonexistent")
        }
    }

    @Test
    fun `addAdminResponse should set admin response and timestamp`() {
        val review = createReview("rev-1")
        every { reviewRepository.findById("rev-1") } returns Optional.of(review)
        every { reviewRepository.save(any()) } answers { firstArg() }

        val request = AdminRespondReviewRequest(response = "Tesekkur ederiz!")
        val result = reviewService.addAdminResponse("rev-1", request)

        assertEquals("Tesekkur ederiz!", result.adminResponse)
        assertNotNull(result.adminResponseAt)
    }

    @Test
    fun `addAdminResponse should throw when review not found`() {
        every { reviewRepository.findById("nonexistent") } returns Optional.empty()

        val request = AdminRespondReviewRequest(response = "Thanks")

        assertThrows<ResourceNotFoundException> {
            reviewService.addAdminResponse("nonexistent", request)
        }
    }

    @Test
    fun `delete should remove review`() {
        val review = createReview("rev-1")
        every { reviewRepository.findById("rev-1") } returns Optional.of(review)
        every { reviewRepository.delete(review) } just Runs

        reviewService.delete("rev-1")

        verify { reviewRepository.delete(review) }
    }

    @Test
    fun `delete should throw when review not found`() {
        every { reviewRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            reviewService.delete("nonexistent")
        }
    }

    private fun createReview(id: String = "rev-1"): Review {
        return Review(
            id = id,
            rating = 5,
            comment = "Great service",
            clientName = "Ali Veli"
        ).apply { tenantId = this@ReviewServiceTest.tenantId }
    }

    private fun createUser(id: String, firstName: String, lastName: String): User {
        return User(
            id = id,
            firstName = firstName,
            lastName = lastName,
            email = "${firstName.lowercase()}@example.com",
            role = Role.CLIENT
        ).apply { tenantId = this@ReviewServiceTest.tenantId }
    }

    private fun createPrincipal(userId: String): UserPrincipal {
        return UserPrincipal(
            id = userId,
            email = "test@example.com",
            tenantId = tenantId,
            role = Role.CLIENT,
            passwordHash = "hashed"
        )
    }
}
