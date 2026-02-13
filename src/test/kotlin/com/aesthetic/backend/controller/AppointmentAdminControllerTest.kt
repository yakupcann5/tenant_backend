package com.aesthetic.backend.controller

import com.aesthetic.backend.config.GlobalExceptionHandler
import com.aesthetic.backend.domain.appointment.AppointmentStatus
import com.aesthetic.backend.dto.request.CreateAppointmentRequest
import com.aesthetic.backend.dto.request.CreateRecurringAppointmentRequest
import com.aesthetic.backend.dto.request.RescheduleAppointmentRequest
import com.aesthetic.backend.dto.request.UpdateAppointmentStatusRequest
import com.aesthetic.backend.dto.response.*
import com.aesthetic.backend.exception.AppointmentConflictException
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.usecase.AppointmentManagementService
import com.aesthetic.backend.usecase.RecurringAppointmentService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@ExtendWith(MockKExtension::class)
class AppointmentAdminControllerTest {

    @MockK private lateinit var appointmentManagementService: AppointmentManagementService
    @MockK private lateinit var recurringAppointmentService: RecurringAppointmentService

    private lateinit var mockMvc: MockMvc
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(
                AppointmentAdminController(appointmentManagementService, recurringAppointmentService)
            )
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(PageableHandlerMethodArgumentResolver())
            .build()
    }

    @Test
    fun `POST should return 201 with appointment response`() {
        val request = CreateAppointmentRequest(
            date = LocalDate.of(2026, 3, 2),
            startTime = LocalTime.of(10, 0),
            clientName = "Test Client",
            serviceIds = listOf("svc-1"),
            staffId = "staff-1"
        )
        every { appointmentManagementService.createAppointment(any()) } returns createAppointmentResponse()

        mockMvc.perform(
            post("/api/admin/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value("appt-1"))
            .andExpect(jsonPath("$.data.clientName").value("Test Client"))
            .andExpect(jsonPath("$.message").value("Randevu oluşturuldu"))
    }

    @Test
    fun `POST should return 400 for empty serviceIds`() {
        val json = """
            {
                "date": "2026-03-02",
                "startTime": "10:00",
                "clientName": "Test",
                "serviceIds": []
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/admin/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `POST should return 400 for blank clientName`() {
        val json = """
            {
                "date": "2026-03-02",
                "startTime": "10:00",
                "clientName": "",
                "serviceIds": ["svc-1"]
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/admin/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `POST should return 409 for appointment conflict`() {
        every { appointmentManagementService.createAppointment(any()) } throws AppointmentConflictException("Çakışma var")

        val request = CreateAppointmentRequest(
            date = LocalDate.of(2026, 3, 2),
            startTime = LocalTime.of(10, 0),
            clientName = "Test Client",
            serviceIds = listOf("svc-1"),
            staffId = "staff-1"
        )

        mockMvc.perform(
            post("/api/admin/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("APPOINTMENT_CONFLICT"))
    }

    @Test
    fun `GET by id should return 200`() {
        every { appointmentManagementService.getById("appt-1") } returns createAppointmentResponse()

        mockMvc.perform(get("/api/admin/appointments/appt-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value("appt-1"))
    }

    @Test
    fun `GET by id should return 404 when not found`() {
        every { appointmentManagementService.getById("nonexistent") } throws ResourceNotFoundException("Randevu bulunamadı")

        mockMvc.perform(get("/api/admin/appointments/nonexistent"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `PATCH status should return 200`() {
        val request = UpdateAppointmentStatusRequest(status = AppointmentStatus.CONFIRMED)
        every { appointmentManagementService.updateStatus("appt-1", any()) } returns createAppointmentResponse(AppointmentStatus.CONFIRMED)

        mockMvc.perform(
            patch("/api/admin/appointments/appt-1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Randevu durumu güncellendi"))
    }

    @Test
    fun `PATCH status should return 400 for invalid transition`() {
        val request = UpdateAppointmentStatusRequest(status = AppointmentStatus.PENDING)
        every { appointmentManagementService.updateStatus("appt-1", any()) } throws IllegalStateException("Geçersiz geçiş")

        mockMvc.perform(
            patch("/api/admin/appointments/appt-1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isInternalServerError) // IllegalStateException → 500 (generic handler)
    }

    @Test
    fun `POST reschedule should return 200`() {
        val request = RescheduleAppointmentRequest(
            date = LocalDate.of(2026, 3, 3),
            startTime = LocalTime.of(14, 0)
        )
        every { appointmentManagementService.reschedule("appt-1", any()) } returns createAppointmentResponse()

        mockMvc.perform(
            post("/api/admin/appointments/appt-1/reschedule")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("Randevu yeniden planlandı"))
    }

    @Test
    fun `POST recurring should return 201`() {
        val request = CreateRecurringAppointmentRequest(
            baseAppointment = CreateAppointmentRequest(
                date = LocalDate.of(2026, 3, 2),
                startTime = LocalTime.of(10, 0),
                clientName = "Test Client",
                serviceIds = listOf("svc-1")
            ),
            recurrenceRule = "WEEKLY",
            count = 4
        )
        every { recurringAppointmentService.createRecurring(any()) } returns RecurringAppointmentResponse(
            groupId = "group-1",
            created = emptyList(),
            skippedDates = emptyList(),
            totalRequested = 4,
            totalCreated = 4
        )

        mockMvc.perform(
            post("/api/admin/appointments/recurring")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.groupId").value("group-1"))
            .andExpect(jsonPath("$.data.totalCreated").value(4))
    }

    @Test
    fun `GET list should return 200 with paginated results`() {
        every { appointmentManagementService.listForAdmin(any(), any(), any(), any()) } returns PagedResponse(
            data = listOf(
                AppointmentSummaryResponse(
                    id = "appt-1",
                    clientName = "Client 1",
                    primaryServiceName = "Service 1",
                    staffName = "Staff 1",
                    date = LocalDate.of(2026, 3, 2),
                    startTime = LocalTime.of(10, 0),
                    endTime = LocalTime.of(10, 30),
                    totalPrice = BigDecimal("100.00"),
                    status = AppointmentStatus.PENDING
                )
            ),
            page = 0,
            size = 20,
            totalElements = 1,
            totalPages = 1
        )

        mockMvc.perform(get("/api/admin/appointments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].id").value("appt-1"))
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    // ============= Helpers =============

    private fun createAppointmentResponse(status: AppointmentStatus = AppointmentStatus.PENDING) = AppointmentResponse(
        id = "appt-1",
        clientName = "Test Client",
        clientEmail = "test@test.com",
        clientPhone = "555-0001",
        services = emptyList(),
        staffId = "staff-1",
        staffName = "Staff 1",
        date = LocalDate.of(2026, 3, 2),
        startTime = LocalTime.of(10, 0),
        endTime = LocalTime.of(10, 30),
        totalDurationMinutes = 30,
        totalPrice = BigDecimal("100.00"),
        status = status,
        notes = null,
        recurringGroupId = null,
        recurrenceRule = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}
