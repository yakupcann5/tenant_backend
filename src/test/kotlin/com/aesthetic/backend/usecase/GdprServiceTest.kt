package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.gdpr.ConsentRecord
import com.aesthetic.backend.domain.gdpr.ConsentType
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.GrantConsentRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.*
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
import java.time.Instant
import java.util.*

@ExtendWith(MockKExtension::class)
class GdprServiceTest {

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var appointmentRepository: AppointmentRepository

    @MockK
    private lateinit var reviewRepository: ReviewRepository

    @MockK
    private lateinit var clientNoteRepository: ClientNoteRepository

    @MockK
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @MockK
    private lateinit var consentRecordRepository: ConsentRecordRepository

    @MockK
    private lateinit var auditLogService: AuditLogService

    private lateinit var gdprService: GdprService

    private val tenantId = "test-tenant-id"
    private val userId = "user-1"

    @BeforeEach
    fun setUp() {
        gdprService = GdprService(
            userRepository,
            appointmentRepository,
            reviewRepository,
            clientNoteRepository,
            refreshTokenRepository,
            consentRecordRepository,
            auditLogService
        )
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `exportUserData should return user data with appointments and consents`() {
        val user = createUser()
        val appointments = PageImpl(emptyList<com.aesthetic.backend.domain.appointment.Appointment>(), PageRequest.of(0, 1000), 0)
        val consents = listOf(createConsentRecord())

        every { userRepository.findById(userId) } returns Optional.of(user)
        every { appointmentRepository.findByClientId(tenantId, userId, null, any()) } returns appointments
        every { consentRecordRepository.findAllByUserIdAndTenantId(userId, tenantId) } returns consents
        every { auditLogService.log(any(), any(), any(), any(), any()) } just Runs

        val result = gdprService.exportUserData(userId)

        assertNotNull(result)
        assertEquals("Ali", result.user.firstName)
        assertEquals("Yilmaz", result.user.lastName)
        assertTrue(result.appointments.isEmpty())
        assertEquals(1, result.consents.size)
        verify { auditLogService.log("EXPORT_USER_DATA", "User", userId) }
    }

    @Test
    fun `exportUserData should throw when user not found`() {
        every { userRepository.findById(userId) } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            gdprService.exportUserData(userId)
        }
    }

    @Test
    fun `anonymizeUser should call all anonymization repos and update user`() {
        val user = createUser()

        every { appointmentRepository.anonymizeByClientId(userId) } just Runs
        every { reviewRepository.anonymizeByUserId(userId) } just Runs
        every { clientNoteRepository.deleteAllByClientId(userId) } just Runs
        every { refreshTokenRepository.deleteByUserId(userId) } just Runs
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }
        every { auditLogService.log(any(), any(), any(), any(), any()) } just Runs

        gdprService.anonymizeUser(userId)

        verify { appointmentRepository.anonymizeByClientId(userId) }
        verify { reviewRepository.anonymizeByUserId(userId) }
        verify { clientNoteRepository.deleteAllByClientId(userId) }
        verify { refreshTokenRepository.deleteByUserId(userId) }
        verify { userRepository.save(match { it.firstName == "Anonim" && it.lastName == "" && it.phone == "" && !it.isActive && it.passwordHash == null }) }
        verify { auditLogService.log("ANONYMIZE_USER", "User", userId) }
    }

    @Test
    fun `anonymizeUser should throw when user not found`() {
        every { appointmentRepository.anonymizeByClientId(userId) } just Runs
        every { reviewRepository.anonymizeByUserId(userId) } just Runs
        every { clientNoteRepository.deleteAllByClientId(userId) } just Runs
        every { refreshTokenRepository.deleteByUserId(userId) } just Runs
        every { userRepository.findById(userId) } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            gdprService.anonymizeUser(userId)
        }
    }

    @Test
    fun `grantConsent should create new consent record when none exists`() {
        val request = GrantConsentRequest(consentType = ConsentType.MARKETING_EMAIL)

        every { consentRecordRepository.findByUserIdAndTenantIdAndConsentType(userId, tenantId, ConsentType.MARKETING_EMAIL) } returns null
        every { consentRecordRepository.save(any()) } answers {
            (firstArg() as ConsentRecord).apply {
                val idField = ConsentRecord::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "consent-1")
            }
        }

        val result = gdprService.grantConsent(userId, request, "192.168.1.1")

        assertEquals("consent-1", result.id)
        assertEquals(ConsentType.MARKETING_EMAIL, result.consentType)
        assertTrue(result.isGranted)
        assertEquals("192.168.1.1", result.ipAddress)
    }

    @Test
    fun `grantConsent should update existing consent record`() {
        val request = GrantConsentRequest(consentType = ConsentType.MARKETING_EMAIL)
        val existing = createConsentRecord().apply {
            isGranted = false
            revokedAt = Instant.now()
        }

        every { consentRecordRepository.findByUserIdAndTenantIdAndConsentType(userId, tenantId, ConsentType.MARKETING_EMAIL) } returns existing
        every { consentRecordRepository.save(any()) } answers { firstArg() }

        val result = gdprService.grantConsent(userId, request, "10.0.0.1")

        assertTrue(result.isGranted)
        assertNull(result.revokedAt)
        verify { consentRecordRepository.save(match { it.isGranted && it.revokedAt == null }) }
    }

    @Test
    fun `revokeConsent should mark consent as revoked`() {
        val existing = createConsentRecord()

        every { consentRecordRepository.findByUserIdAndTenantIdAndConsentType(userId, tenantId, ConsentType.MARKETING_EMAIL) } returns existing
        every { consentRecordRepository.save(any()) } answers { firstArg() }

        val result = gdprService.revokeConsent(userId, ConsentType.MARKETING_EMAIL)

        assertFalse(result.isGranted)
        assertNotNull(result.revokedAt)
    }

    @Test
    fun `revokeConsent should throw when consent record not found`() {
        every { consentRecordRepository.findByUserIdAndTenantIdAndConsentType(userId, tenantId, ConsentType.MARKETING_EMAIL) } returns null

        assertThrows<ResourceNotFoundException> {
            gdprService.revokeConsent(userId, ConsentType.MARKETING_EMAIL)
        }
    }

    @Test
    fun `getConsents should return all consent records for user`() {
        val consents = listOf(
            createConsentRecord(),
            createConsentRecord(id = "consent-2", type = ConsentType.PRIVACY_POLICY)
        )

        every { consentRecordRepository.findAllByUserIdAndTenantId(userId, tenantId) } returns consents

        val result = gdprService.getConsents(userId)

        assertEquals(2, result.size)
    }

    private fun createUser() = User(
        id = userId,
        firstName = "Ali",
        lastName = "Yilmaz",
        email = "ali@example.com",
        phone = "05551234567",
        role = Role.CLIENT
    ).apply { tenantId = this@GdprServiceTest.tenantId }

    private fun createConsentRecord(
        id: String = "consent-1",
        type: ConsentType = ConsentType.MARKETING_EMAIL
    ) = ConsentRecord(
        id = id,
        userId = userId,
        consentType = type,
        grantedAt = Instant.now(),
        ipAddress = "192.168.1.1",
        isGranted = true
    ).apply { tenantId = this@GdprServiceTest.tenantId }
}
