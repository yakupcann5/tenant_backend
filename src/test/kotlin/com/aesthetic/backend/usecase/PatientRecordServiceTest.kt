package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.patient.PatientRecord
import com.aesthetic.backend.domain.patient.TreatmentHistory
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.CreateTreatmentRequest
import com.aesthetic.backend.dto.request.UpdateTreatmentRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.PatientRecordRepository
import com.aesthetic.backend.repository.TreatmentHistoryRepository
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
import java.time.LocalDate
import java.util.*

@ExtendWith(MockKExtension::class)
class PatientRecordServiceTest {

    @MockK
    private lateinit var patientRecordRepository: PatientRecordRepository

    @MockK
    private lateinit var treatmentHistoryRepository: TreatmentHistoryRepository

    @MockK
    private lateinit var entityManager: EntityManager

    private lateinit var patientRecordService: PatientRecordService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        patientRecordService = PatientRecordService(
            patientRecordRepository,
            treatmentHistoryRepository,
            entityManager
        )
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `getOrCreateRecord should return existing record`() {
        val clientUser = createClientUser()
        val existingRecord = createPatientRecord(clientUser)
        every { patientRecordRepository.findByClientIdAndTenantId("client-1", tenantId) } returns existingRecord

        val result = patientRecordService.getOrCreateRecord("client-1")

        assertEquals("record-1", result.id)
        assertEquals("client-1", result.clientId)
        assertEquals("A+", result.bloodType)
        verify(exactly = 0) { patientRecordRepository.save(any()) }
    }

    @Test
    fun `getOrCreateRecord should create new record when not exists`() {
        val clientRef = mockk<User>(relaxed = true)
        every { clientRef.id } returns "client-1"
        every { patientRecordRepository.findByClientIdAndTenantId("client-1", tenantId) } returns null
        every { entityManager.getReference(User::class.java, "client-1") } returns clientRef
        every { patientRecordRepository.save(any()) } answers {
            (firstArg() as PatientRecord).apply {
                val idField = PatientRecord::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "record-new")
            }
        }

        val result = patientRecordService.getOrCreateRecord("client-1")

        assertEquals("record-new", result.id)
        assertEquals("client-1", result.clientId)
        verify { entityManager.getReference(User::class.java, "client-1") }
        verify { patientRecordRepository.save(any()) }
    }

    @Test
    fun `addTreatment should save new treatment history`() {
        val clientRef = mockk<User>(relaxed = true)
        every { clientRef.id } returns "client-1"
        every { clientRef.firstName } returns "Mehmet"
        every { clientRef.lastName } returns "Demir"

        val performerRef = mockk<User>(relaxed = true)
        every { performerRef.id } returns "staff-1"
        every { performerRef.firstName } returns "Dr. Ayse"
        every { performerRef.lastName } returns "Yilmaz"

        every { entityManager.getReference(User::class.java, "client-1") } returns clientRef
        every { entityManager.getReference(User::class.java, "staff-1") } returns performerRef
        every { treatmentHistoryRepository.save(any()) } answers {
            (firstArg() as TreatmentHistory).apply {
                val idField = TreatmentHistory::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "treatment-1")
            }
        }

        val savedTreatment = TreatmentHistory(
            id = "treatment-1",
            client = clientRef,
            performedBy = performerRef,
            treatmentDate = LocalDate.of(2025, 3, 15),
            title = "Botox",
            description = "Forehead treatment",
            notes = "No side effects"
        ).apply { tenantId = this@PatientRecordServiceTest.tenantId }
        every { treatmentHistoryRepository.findByIdWithPerformedBy("treatment-1") } returns savedTreatment

        val principal = UserPrincipal(
            id = "staff-1",
            email = "staff@example.com",
            tenantId = tenantId,
            role = Role.STAFF,
            passwordHash = "hashed"
        )
        val request = CreateTreatmentRequest(
            treatmentDate = LocalDate.of(2025, 3, 15),
            title = "Botox",
            description = "Forehead treatment",
            notes = "No side effects"
        )
        val result = patientRecordService.addTreatment("client-1", request, principal)

        assertEquals("treatment-1", result.id)
        assertEquals("client-1", result.clientId)
        assertEquals("Botox", result.title)
        assertEquals("Forehead treatment", result.description)
        assertEquals("No side effects", result.notes)
        assertEquals(LocalDate.of(2025, 3, 15), result.treatmentDate)
        assertEquals("Dr. Ayse Yilmaz", result.performedByName)

        verify { entityManager.getReference(User::class.java, "client-1") }
        verify { entityManager.getReference(User::class.java, "staff-1") }
    }

    @Test
    fun `updateTreatment should update provided fields`() {
        val clientUser = createClientUser()
        val performerUser = createStaffUser()
        val treatment = TreatmentHistory(
            id = "treatment-1",
            client = clientUser,
            performedBy = performerUser,
            treatmentDate = LocalDate.of(2025, 3, 15),
            title = "Botox",
            description = "Original description",
            notes = "Original notes"
        ).apply { tenantId = this@PatientRecordServiceTest.tenantId }

        every { treatmentHistoryRepository.findById("treatment-1") } returns Optional.of(treatment)
        every { treatmentHistoryRepository.save(any()) } answers { firstArg() }

        val updatedTreatment = treatment.apply {
            title = "Updated Botox"
            description = "Updated description"
        }
        every { treatmentHistoryRepository.findByIdWithPerformedBy("treatment-1") } returns updatedTreatment

        val request = UpdateTreatmentRequest(
            title = "Updated Botox",
            description = "Updated description"
        )
        val result = patientRecordService.updateTreatment("treatment-1", request)

        assertEquals("treatment-1", result.id)
        assertEquals("Updated Botox", result.title)
        assertEquals("Updated description", result.description)
    }

    @Test
    fun `updateTreatment should throw when not found`() {
        every { treatmentHistoryRepository.findById("nonexistent") } returns Optional.empty()

        val request = UpdateTreatmentRequest(title = "Updated")

        assertThrows<ResourceNotFoundException> {
            patientRecordService.updateTreatment("nonexistent", request)
        }
    }

    @Test
    fun `deleteTreatment should remove treatment`() {
        val treatment = TreatmentHistory(
            id = "treatment-1",
            client = createClientUser(),
            treatmentDate = LocalDate.of(2025, 3, 15),
            title = "Botox"
        ).apply { tenantId = this@PatientRecordServiceTest.tenantId }

        every { treatmentHistoryRepository.findById("treatment-1") } returns Optional.of(treatment)
        every { treatmentHistoryRepository.delete(treatment) } just Runs

        patientRecordService.deleteTreatment("treatment-1")

        verify { treatmentHistoryRepository.delete(treatment) }
    }

    @Test
    fun `deleteTreatment should throw when not found`() {
        every { treatmentHistoryRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            patientRecordService.deleteTreatment("nonexistent")
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
    ).apply { tenantId = this@PatientRecordServiceTest.tenantId }

    private fun createStaffUser(id: String = "staff-1") = User(
        id = id,
        firstName = "Dr. Ayse",
        lastName = "Yilmaz",
        email = "staff@example.com",
        passwordHash = "hashed-password",
        role = Role.STAFF
    ).apply { tenantId = this@PatientRecordServiceTest.tenantId }

    private fun createPatientRecord(client: User, id: String = "record-1") = PatientRecord(
        id = id,
        client = client,
        bloodType = "A+",
        allergies = "Pollen",
        chronicConditions = "",
        currentMedications = "",
        medicalNotes = ""
    ).apply { tenantId = this@PatientRecordServiceTest.tenantId }
}
