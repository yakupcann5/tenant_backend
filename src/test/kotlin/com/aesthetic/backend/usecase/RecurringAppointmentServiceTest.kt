package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.appointment.Appointment
import com.aesthetic.backend.domain.appointment.AppointmentStatus
import com.aesthetic.backend.dto.request.CreateAppointmentRequest
import com.aesthetic.backend.dto.request.CreateRecurringAppointmentRequest
import com.aesthetic.backend.dto.response.AppointmentResponse
import com.aesthetic.backend.exception.AppointmentConflictException
import com.aesthetic.backend.repository.AppointmentRepository
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.*

@ExtendWith(MockKExtension::class)
class RecurringAppointmentServiceTest {

    @MockK private lateinit var appointmentManagementService: AppointmentManagementService
    @MockK private lateinit var appointmentRepository: AppointmentRepository

    private lateinit var service: RecurringAppointmentService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        service = RecurringAppointmentService(appointmentManagementService, appointmentRepository)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `createRecurring WEEKLY should create 4 appointments`() {
        val baseRequest = createBaseRequest(LocalDate.of(2026, 3, 2))
        val request = CreateRecurringAppointmentRequest(
            baseAppointment = baseRequest,
            recurrenceRule = "WEEKLY",
            count = 4
        )

        every { appointmentManagementService.createAppointment(any()) } returns createAppointmentResponse()
        every { appointmentRepository.findById(any()) } returns Optional.of(createAppointment())
        every { appointmentRepository.save(any()) } returns createAppointment()

        val result = service.createRecurring(request)

        assertEquals(4, result.totalCreated)
        assertEquals(0, result.skippedDates.size)
        assertEquals(4, result.totalRequested)
    }

    @Test
    fun `createRecurring BIWEEKLY should create appointments with 2 week intervals`() {
        val baseRequest = createBaseRequest(LocalDate.of(2026, 3, 2))
        val request = CreateRecurringAppointmentRequest(
            baseAppointment = baseRequest,
            recurrenceRule = "BIWEEKLY",
            count = 3
        )

        val capturedRequests = mutableListOf<CreateAppointmentRequest>()
        every { appointmentManagementService.createAppointment(capture(capturedRequests)) } returns createAppointmentResponse()
        every { appointmentRepository.findById(any()) } returns Optional.of(createAppointment())
        every { appointmentRepository.save(any()) } returns createAppointment()

        val result = service.createRecurring(request)

        assertEquals(3, result.totalCreated)
        assertEquals(LocalDate.of(2026, 3, 2), capturedRequests[0].date)
        assertEquals(LocalDate.of(2026, 3, 16), capturedRequests[1].date)
        assertEquals(LocalDate.of(2026, 3, 30), capturedRequests[2].date)
    }

    @Test
    fun `createRecurring MONTHLY should create appointments with monthly intervals`() {
        val baseRequest = createBaseRequest(LocalDate.of(2026, 3, 1))
        val request = CreateRecurringAppointmentRequest(
            baseAppointment = baseRequest,
            recurrenceRule = "MONTHLY",
            count = 3
        )

        val capturedRequests = mutableListOf<CreateAppointmentRequest>()
        every { appointmentManagementService.createAppointment(capture(capturedRequests)) } returns createAppointmentResponse()
        every { appointmentRepository.findById(any()) } returns Optional.of(createAppointment())
        every { appointmentRepository.save(any()) } returns createAppointment()

        val result = service.createRecurring(request)

        assertEquals(3, result.totalCreated)
        assertEquals(LocalDate.of(2026, 3, 1), capturedRequests[0].date)
        assertEquals(LocalDate.of(2026, 4, 1), capturedRequests[1].date)
        assertEquals(LocalDate.of(2026, 5, 1), capturedRequests[2].date)
    }

    @Test
    fun `createRecurring should handle partial success`() {
        val baseRequest = createBaseRequest(LocalDate.of(2026, 3, 2))
        val request = CreateRecurringAppointmentRequest(
            baseAppointment = baseRequest,
            recurrenceRule = "WEEKLY",
            count = 4
        )

        // First and third succeed, second and fourth fail
        every { appointmentManagementService.createAppointment(match { it.date == LocalDate.of(2026, 3, 2) }) } returns createAppointmentResponse()
        every { appointmentManagementService.createAppointment(match { it.date == LocalDate.of(2026, 3, 9) }) } throws AppointmentConflictException("Çakışma")
        every { appointmentManagementService.createAppointment(match { it.date == LocalDate.of(2026, 3, 16) }) } returns createAppointmentResponse()
        every { appointmentManagementService.createAppointment(match { it.date == LocalDate.of(2026, 3, 23) }) } throws AppointmentConflictException("Çakışma")
        every { appointmentRepository.findById(any()) } returns Optional.of(createAppointment())
        every { appointmentRepository.save(any()) } returns createAppointment()

        val result = service.createRecurring(request)

        assertEquals(2, result.totalCreated)
        assertEquals(2, result.skippedDates.size)
        assertEquals(4, result.totalRequested)
        assertTrue(result.skippedDates.contains(LocalDate.of(2026, 3, 9)))
        assertTrue(result.skippedDates.contains(LocalDate.of(2026, 3, 23)))
    }

    @Test
    fun `createRecurring should throw for invalid recurrence rule`() {
        val baseRequest = createBaseRequest(LocalDate.of(2026, 3, 2))
        val request = CreateRecurringAppointmentRequest(
            baseAppointment = baseRequest,
            recurrenceRule = "DAILY",
            count = 4
        )

        assertThrows<IllegalArgumentException> {
            service.createRecurring(request)
        }
    }

    // ============= Helpers =============

    private fun createBaseRequest(date: LocalDate) = CreateAppointmentRequest(
        date = date,
        startTime = LocalTime.of(10, 0),
        clientName = "Test Client",
        serviceIds = listOf("svc-1"),
        staffId = "staff-1"
    )

    private fun createAppointmentResponse() = AppointmentResponse(
        id = UUID.randomUUID().toString(),
        clientName = "Test Client",
        clientEmail = "",
        clientPhone = "",
        services = emptyList(),
        staffId = "staff-1",
        staffName = "Staff 1",
        date = LocalDate.now(),
        startTime = LocalTime.of(10, 0),
        endTime = LocalTime.of(10, 30),
        totalDurationMinutes = 30,
        totalPrice = BigDecimal("100.00"),
        status = AppointmentStatus.PENDING,
        notes = null,
        recurringGroupId = null,
        recurrenceRule = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    private fun createAppointment() = Appointment(
        id = UUID.randomUUID().toString(),
        status = AppointmentStatus.PENDING
    ).apply { tenantId = this@RecurringAppointmentServiceTest.tenantId }
}
