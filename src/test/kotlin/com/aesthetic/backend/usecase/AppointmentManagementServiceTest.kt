package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.appointment.Appointment
import com.aesthetic.backend.domain.appointment.AppointmentServiceEntity
import com.aesthetic.backend.domain.appointment.AppointmentStatus
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.CreateAppointmentRequest
import com.aesthetic.backend.dto.request.RescheduleAppointmentRequest
import com.aesthetic.backend.dto.request.UpdateAppointmentStatusRequest
import com.aesthetic.backend.dto.response.SiteSettingsResponse
import com.aesthetic.backend.exception.AppointmentConflictException
import com.aesthetic.backend.exception.ClientBlacklistedException
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.repository.ServiceRepository
import com.aesthetic.backend.repository.UserRepository
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
import org.springframework.security.access.AccessDeniedException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.*

@ExtendWith(MockKExtension::class)
class AppointmentManagementServiceTest {

    @MockK private lateinit var appointmentRepository: AppointmentRepository
    @MockK private lateinit var serviceRepository: ServiceRepository
    @MockK private lateinit var userRepository: UserRepository
    @MockK private lateinit var siteSettingsService: SiteSettingsService
    @MockK private lateinit var availabilityService: AvailabilityService
    @MockK private lateinit var notificationService: NotificationService
    @MockK private lateinit var entityManager: EntityManager

    private lateinit var service: AppointmentManagementService

    private val tenantId = "test-tenant-id"
    private val staffId = "staff-1"
    private val clientId = "client-1"

    @BeforeEach
    fun setUp() {
        service = AppointmentManagementService(
            appointmentRepository, serviceRepository, userRepository,
            siteSettingsService, availabilityService, notificationService, entityManager
        )
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    // ============= createAppointment =============

    @Test
    fun `createAppointment should create appointment successfully`() {
        val request = createRequest()
        mockSuccessfulCreation()

        val result = service.createAppointment(request)

        assertNotNull(result)
        assertEquals("Test Client", result.clientName)
        verify { appointmentRepository.save(any()) }
    }

    @Test
    fun `createAppointment should throw when client is blacklisted`() {
        val request = createRequest()
        every { siteSettingsService.getSettings() } returns createSettings()

        val blacklistedClient = createUser(clientId, "Client", Role.CLIENT).apply {
            isBlacklisted = true
        }
        every { userRepository.findById(clientId) } returns Optional.of(blacklistedClient)

        assertThrows<ClientBlacklistedException> {
            service.createAppointment(request, clientId)
        }
    }

    @Test
    fun `createAppointment should throw for past date`() {
        val request = createRequest(date = LocalDate.of(2020, 1, 1))
        every { siteSettingsService.getSettings() } returns createSettings()

        assertThrows<IllegalArgumentException> {
            service.createAppointment(request)
        }
    }

    @Test
    fun `createAppointment should throw when service not found`() {
        val request = createRequest()
        every { siteSettingsService.getSettings() } returns createSettings()
        every { serviceRepository.findAllById(any()) } returns emptyList()

        assertThrows<ResourceNotFoundException> {
            service.createAppointment(request)
        }
    }

    @Test
    fun `createAppointment should throw on conflict`() {
        val request = createRequest()
        val svc = createServiceEntity("svc-1", 30)

        every { siteSettingsService.getSettings() } returns createSettings()
        every { serviceRepository.findAllById(listOf("svc-1")) } returns listOf(svc)
        every { availabilityService.isWithinWorkingHours(any(), any(), any(), any(), any()) } returns true
        every { availabilityService.isTimeSlotBlocked(any(), any(), any(), any(), any()) } returns false
        every { appointmentRepository.findConflictingAppointments(any(), any(), any(), any(), any(), any()) } returns listOf(mockk())

        assertThrows<AppointmentConflictException> {
            service.createAppointment(request)
        }
    }

    @Test
    fun `createAppointment should throw when outside working hours`() {
        val request = createRequest()
        val svc = createServiceEntity("svc-1", 30)

        every { siteSettingsService.getSettings() } returns createSettings()
        every { serviceRepository.findAllById(listOf("svc-1")) } returns listOf(svc)
        every { appointmentRepository.findConflictingAppointments(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { availabilityService.isWithinWorkingHours(any(), any(), any(), any(), any()) } returns false

        assertThrows<AppointmentConflictException> {
            service.createAppointment(request)
        }
    }

    @Test
    fun `createAppointment should throw when slot is blocked`() {
        val request = createRequest()
        val svc = createServiceEntity("svc-1", 30)

        every { siteSettingsService.getSettings() } returns createSettings()
        every { serviceRepository.findAllById(listOf("svc-1")) } returns listOf(svc)
        every { appointmentRepository.findConflictingAppointments(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { availabilityService.isWithinWorkingHours(any(), any(), any(), any(), any()) } returns true
        every { availabilityService.isTimeSlotBlocked(any(), any(), any(), any(), any()) } returns true

        assertThrows<AppointmentConflictException> {
            service.createAppointment(request)
        }
    }

    @Test
    fun `createAppointment with auto staff assignment`() {
        val request = createRequest(staffId = null)
        val svc = createServiceEntity("svc-1", 30)

        every { siteSettingsService.getSettings() } returns createSettings()
        every { serviceRepository.findAllById(listOf("svc-1")) } returns listOf(svc)
        every { availabilityService.findAvailableStaff(any(), any(), any(), any()) } returns staffId
        every { appointmentRepository.findConflictingAppointments(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { availabilityService.isWithinWorkingHours(any(), any(), any(), any(), any()) } returns true
        every { availabilityService.isTimeSlotBlocked(any(), any(), any(), any(), any()) } returns false
        mockSaveAndFetch()

        val result = service.createAppointment(request)

        assertNotNull(result)
        verify { availabilityService.findAvailableStaff(any(), any(), any(), any()) }
    }

    @Test
    fun `createAppointment with auto staff assignment throws when no staff available`() {
        val request = createRequest(staffId = null)
        val svc = createServiceEntity("svc-1", 30)

        every { siteSettingsService.getSettings() } returns createSettings()
        every { serviceRepository.findAllById(listOf("svc-1")) } returns listOf(svc)
        every { availabilityService.findAvailableStaff(any(), any(), any(), any()) } returns null

        assertThrows<AppointmentConflictException> {
            service.createAppointment(request)
        }
    }

    @Test
    fun `createAppointment resolves existing user by email for public appointments`() {
        val request = createRequest(clientEmail = "existing@test.com")
        val existingUser = createUser("existing-user-id", "Existing User", Role.CLIENT)

        every { siteSettingsService.getSettings() } returns createSettings()
        every { userRepository.findByEmailAndTenantId("existing@test.com", tenantId) } returns existingUser
        every { serviceRepository.findAllById(listOf("svc-1")) } returns listOf(createServiceEntity("svc-1", 30))
        every { appointmentRepository.findConflictingAppointments(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { availabilityService.isWithinWorkingHours(any(), any(), any(), any(), any()) } returns true
        every { availabilityService.isTimeSlotBlocked(any(), any(), any(), any(), any()) } returns false
        mockSaveAndFetch()

        val result = service.createAppointment(request)

        assertNotNull(result)
    }

    @Test
    fun `createAppointment throws when resolved email user is blacklisted`() {
        val request = createRequest(clientEmail = "blacklisted@test.com")
        val blacklistedUser = createUser("bl-user", "Blacklisted", Role.CLIENT).apply {
            isBlacklisted = true
        }

        every { siteSettingsService.getSettings() } returns createSettings()
        every { userRepository.findByEmailAndTenantId("blacklisted@test.com", tenantId) } returns blacklistedUser

        assertThrows<ClientBlacklistedException> {
            service.createAppointment(request)
        }
    }

    @Test
    fun `createAppointment calculates buffer from last service only`() {
        val svc1 = createServiceEntity("svc-1", 45, bufferMinutes = 10)
        val svc2 = createServiceEntity("svc-2", 60, bufferMinutes = 5)

        val request = createRequest(serviceIds = listOf("svc-1", "svc-2"))

        every { siteSettingsService.getSettings() } returns createSettings()
        every { serviceRepository.findAllById(listOf("svc-1", "svc-2")) } returns listOf(svc1, svc2)
        every { appointmentRepository.findConflictingAppointments(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { availabilityService.isWithinWorkingHours(any(), any(), any(), any(), any()) } returns true
        every { availabilityService.isTimeSlotBlocked(any(), any(), any(), any(), any()) } returns false
        mockSaveAndFetch()

        service.createAppointment(request)

        // Verify conflict check uses endTimeWithBuffer = startTime + 105min + 5min buffer
        verify {
            appointmentRepository.findConflictingAppointments(
                tenantId, staffId, any(), LocalTime.of(10, 0),
                LocalTime.of(11, 50), // 10:00 + 105min + 5min buffer = 11:50
                any()
            )
        }
    }

    // ============= updateStatus =============

    @Test
    fun `updateStatus should transition PENDING to CONFIRMED`() {
        val appointment = createAppointment(AppointmentStatus.PENDING)
        every { appointmentRepository.findById("appt-1") } returns Optional.of(appointment)
        every { appointmentRepository.save(any()) } returns appointment
        every { appointmentRepository.findByIdWithDetails("appt-1") } returns createDetailedAppointment(AppointmentStatus.CONFIRMED)

        val request = UpdateAppointmentStatusRequest(status = AppointmentStatus.CONFIRMED)
        val result = service.updateStatus("appt-1", request)

        assertEquals(AppointmentStatus.CONFIRMED, result.status)
    }

    @Test
    fun `updateStatus should throw for invalid transition`() {
        val appointment = createAppointment(AppointmentStatus.COMPLETED)
        every { appointmentRepository.findById("appt-1") } returns Optional.of(appointment)

        val request = UpdateAppointmentStatusRequest(status = AppointmentStatus.PENDING)

        assertThrows<IllegalStateException> {
            service.updateStatus("appt-1", request)
        }
    }

    @Test
    fun `updateStatus NO_SHOW increments noShowCount`() {
        val client = createUser(clientId, "Client", Role.CLIENT).apply { noShowCount = 1 }
        val appointment = createAppointment(AppointmentStatus.CONFIRMED).apply {
            this.client = client
        }
        every { appointmentRepository.findById("appt-1") } returns Optional.of(appointment)
        every { appointmentRepository.save(any()) } returns appointment
        every { userRepository.save(any()) } returns client
        every { appointmentRepository.findByIdWithDetails("appt-1") } returns createDetailedAppointment(AppointmentStatus.NO_SHOW)

        val request = UpdateAppointmentStatusRequest(status = AppointmentStatus.NO_SHOW)
        service.updateStatus("appt-1", request)

        assertEquals(2, client.noShowCount)
        verify { userRepository.save(client) }
    }

    @Test
    fun `updateStatus NO_SHOW blacklists at threshold`() {
        val client = createUser(clientId, "Client", Role.CLIENT).apply { noShowCount = 2 }
        val appointment = createAppointment(AppointmentStatus.CONFIRMED).apply {
            this.client = client
        }
        every { appointmentRepository.findById("appt-1") } returns Optional.of(appointment)
        every { appointmentRepository.save(any()) } returns appointment
        every { userRepository.save(any()) } returns client
        every { appointmentRepository.findByIdWithDetails("appt-1") } returns createDetailedAppointment(AppointmentStatus.NO_SHOW)

        val request = UpdateAppointmentStatusRequest(status = AppointmentStatus.NO_SHOW)
        service.updateStatus("appt-1", request)

        assertEquals(3, client.noShowCount)
        assertTrue(client.isBlacklisted)
        assertNotNull(client.blacklistedAt)
        assertNotNull(client.blacklistReason)
    }

    @Test
    fun `updateStatus CANCELLED sets cancelledAt and reason`() {
        val appointment = createAppointment(AppointmentStatus.PENDING)
        every { appointmentRepository.findById("appt-1") } returns Optional.of(appointment)
        every { appointmentRepository.save(any()) } returns appointment
        every { appointmentRepository.findByIdWithDetails("appt-1") } returns createDetailedAppointment(AppointmentStatus.CANCELLED)

        val request = UpdateAppointmentStatusRequest(
            status = AppointmentStatus.CANCELLED,
            reason = "Müşteri isteği"
        )
        service.updateStatus("appt-1", request)

        assertNotNull(appointment.cancelledAt)
        assertEquals("Müşteri isteği", appointment.cancellationReason)
    }

    // ============= cancelByClient =============

    @Test
    fun `cancelByClient should cancel successfully`() {
        val client = createUser(clientId, "Client", Role.CLIENT)
        val appointment = createAppointment(AppointmentStatus.CONFIRMED).apply {
            this.client = client
            this.date = LocalDate.now().plusDays(7)
            this.startTime = LocalTime.of(10, 0)
        }
        every { appointmentRepository.findById("appt-1") } returns Optional.of(appointment)
        every { siteSettingsService.getSettings() } returns createSettings()
        every { appointmentRepository.save(any()) } returns appointment
        every { appointmentRepository.findByIdWithDetails("appt-1") } returns createDetailedAppointment(AppointmentStatus.CANCELLED)

        val result = service.cancelByClient("appt-1", clientId)

        assertEquals(AppointmentStatus.CANCELLED, result.status)
    }

    @Test
    fun `cancelByClient should throw when not own appointment`() {
        val client = createUser(clientId, "Client", Role.CLIENT)
        val appointment = createAppointment(AppointmentStatus.CONFIRMED).apply {
            this.client = client
        }
        every { appointmentRepository.findById("appt-1") } returns Optional.of(appointment)

        assertThrows<AccessDeniedException> {
            service.cancelByClient("appt-1", "other-client-id")
        }
    }

    @Test
    fun `cancelByClient should throw when within policy hours`() {
        val client = createUser(clientId, "Client", Role.CLIENT)
        val appointment = createAppointment(AppointmentStatus.CONFIRMED).apply {
            this.client = client
            this.date = LocalDate.now()
            this.startTime = LocalTime.now().plusHours(1) // Less than 24 hours
        }
        every { appointmentRepository.findById("appt-1") } returns Optional.of(appointment)
        every { siteSettingsService.getSettings() } returns createSettings(cancellationPolicyHours = 24)

        assertThrows<IllegalArgumentException> {
            service.cancelByClient("appt-1", clientId)
        }
    }

    // ============= reschedule =============

    @Test
    fun `reschedule should reschedule successfully`() {
        val appointment = createAppointment(AppointmentStatus.PENDING).apply {
            this.totalDurationMinutes = 60
            this.date = LocalDate.now().plusDays(7)
            this.startTime = LocalTime.of(10, 0)
        }
        val request = RescheduleAppointmentRequest(
            date = LocalDate.now().plusDays(8),
            startTime = LocalTime.of(14, 0)
        )

        every { appointmentRepository.findById("appt-1") } returns Optional.of(appointment)
        every { siteSettingsService.getSettings() } returns createSettings()
        every { appointmentRepository.findConflictingAppointments(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { availabilityService.isWithinWorkingHours(any(), any(), any(), any(), any()) } returns true
        every { availabilityService.isTimeSlotBlocked(any(), any(), any(), any(), any()) } returns false
        every { appointmentRepository.save(any()) } returns appointment
        every { appointmentRepository.findByIdWithDetails("appt-1") } returns createDetailedAppointment(AppointmentStatus.PENDING)

        val result = service.reschedule("appt-1", request)

        assertNotNull(result)
    }

    @Test
    fun `reschedule should throw when appointment is completed`() {
        val appointment = createAppointment(AppointmentStatus.COMPLETED)
        every { appointmentRepository.findById("appt-1") } returns Optional.of(appointment)

        val request = RescheduleAppointmentRequest(
            date = LocalDate.now().plusDays(1),
            startTime = LocalTime.of(14, 0)
        )

        assertThrows<IllegalArgumentException> {
            service.reschedule("appt-1", request)
        }
    }

    @Test
    fun `reschedule should throw on conflict`() {
        val otherAppt = createAppointment(AppointmentStatus.CONFIRMED).apply {
            val idField = Appointment::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.set(this, "other-appt")
        }
        val appointment = createAppointment(AppointmentStatus.PENDING).apply {
            this.totalDurationMinutes = 60
            this.date = LocalDate.now().plusDays(7)
            this.startTime = LocalTime.of(10, 0)
        }
        val request = RescheduleAppointmentRequest(
            date = LocalDate.now().plusDays(8),
            startTime = LocalTime.of(14, 0)
        )

        every { appointmentRepository.findById("appt-1") } returns Optional.of(appointment)
        every { siteSettingsService.getSettings() } returns createSettings()
        every { appointmentRepository.findConflictingAppointments(any(), any(), any(), any(), any(), any()) } returns listOf(otherAppt)
        every { availabilityService.isWithinWorkingHours(any(), any(), any(), any(), any()) } returns true
        every { availabilityService.isTimeSlotBlocked(any(), any(), any(), any(), any()) } returns false

        assertThrows<AppointmentConflictException> {
            service.reschedule("appt-1", request)
        }
    }

    // ============= getById =============

    @Test
    fun `getById should throw when not found`() {
        every { appointmentRepository.findByIdWithDetails("nonexistent") } returns null

        assertThrows<ResourceNotFoundException> {
            service.getById("nonexistent")
        }
    }

    @Test
    fun `getById should return appointment`() {
        every { appointmentRepository.findByIdWithDetails("appt-1") } returns createDetailedAppointment(AppointmentStatus.PENDING)

        val result = service.getById("appt-1")

        assertEquals("appt-1", result.id)
    }

    // ============= Helpers =============

    private fun createRequest(
        date: LocalDate = LocalDate.now().plusDays(7),
        staffId: String? = this.staffId,
        serviceIds: List<String> = listOf("svc-1"),
        clientEmail: String? = null
    ) = CreateAppointmentRequest(
        date = date,
        startTime = LocalTime.of(10, 0),
        clientName = "Test Client",
        clientEmail = clientEmail,
        clientPhone = "555-0001",
        serviceIds = serviceIds,
        staffId = staffId
    )

    private fun createAppointment(status: AppointmentStatus): Appointment {
        val staff = mockk<User> { every { id } returns staffId }
        return Appointment(
            id = "appt-1",
            clientName = "Test Client",
            clientEmail = "test@test.com",
            clientPhone = "555-0001",
            date = LocalDate.now().plusDays(7),
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(10, 30),
            totalDurationMinutes = 30,
            totalPrice = BigDecimal("100.00"),
            status = status,
            staff = staff,
            services = mutableListOf()
        ).apply { tenantId = this@AppointmentManagementServiceTest.tenantId }
    }

    private fun createDetailedAppointment(status: AppointmentStatus): Appointment {
        return Appointment(
            id = "appt-1",
            clientName = "Test Client",
            clientEmail = "test@test.com",
            clientPhone = "555-0001",
            date = LocalDate.now().plusDays(7),
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(10, 30),
            totalDurationMinutes = 30,
            totalPrice = BigDecimal("100.00"),
            status = status,
            services = mutableListOf()
        ).apply { tenantId = this@AppointmentManagementServiceTest.tenantId }
    }

    private fun createUser(id: String, name: String, role: Role) = User(
        id = id,
        firstName = name.substringBefore(' '),
        lastName = name.substringAfter(' ', ""),
        email = "$id@test.com",
        role = role
    ).apply { tenantId = this@AppointmentManagementServiceTest.tenantId }

    private fun createServiceEntity(id: String, durationMinutes: Int, bufferMinutes: Int = 0) =
        com.aesthetic.backend.domain.service.Service(
            id = id,
            slug = "svc-$id",
            title = "Service $id",
            durationMinutes = durationMinutes,
            bufferMinutes = bufferMinutes,
            price = BigDecimal("50.00")
        ).apply { tenantId = this@AppointmentManagementServiceTest.tenantId }

    private fun createSettings(cancellationPolicyHours: Int = 24) = SiteSettingsResponse(
        id = "settings-1",
        siteName = "Test Salon",
        phone = "",
        email = "",
        address = "",
        whatsapp = "",
        instagram = "",
        facebook = "",
        twitter = "",
        youtube = "",
        mapEmbedUrl = "",
        timezone = "Europe/Istanbul",
        locale = "tr",
        cancellationPolicyHours = cancellationPolicyHours,
        defaultSlotDurationMinutes = 30,
        autoConfirmAppointments = false,
        themeSettings = "{}",
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    private fun mockSuccessfulCreation() {
        val svc = createServiceEntity("svc-1", 30)

        every { siteSettingsService.getSettings() } returns createSettings()
        every { serviceRepository.findAllById(listOf("svc-1")) } returns listOf(svc)
        every { appointmentRepository.findConflictingAppointments(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { availabilityService.isWithinWorkingHours(any(), any(), any(), any(), any()) } returns true
        every { availabilityService.isTimeSlotBlocked(any(), any(), any(), any(), any()) } returns false
        mockSaveAndFetch()
    }

    private fun mockSaveAndFetch() {
        every { entityManager.getReference(User::class.java, any()) } returns mockk(relaxed = true)
        every { entityManager.getReference(com.aesthetic.backend.domain.service.Service::class.java, any()) } returns mockk(relaxed = true)
        every { appointmentRepository.save(any()) } answers {
            val appt = firstArg<Appointment>()
            val idField = Appointment::class.java.getDeclaredField("id")
            idField.isAccessible = true
            if (idField.get(appt) == null) {
                idField.set(appt, "appt-new")
            }
            appt
        }
        every { appointmentRepository.findByIdWithDetails(any()) } returns createDetailedAppointment(AppointmentStatus.PENDING)
        every { notificationService.toContext(any<Appointment>()) } returns mockk(relaxed = true)
        every { notificationService.sendAppointmentConfirmation(any()) } just runs
        every { notificationService.sendAppointmentCancellation(any()) } just runs
        every { notificationService.sendAppointmentRescheduled(any()) } just runs
        every { notificationService.sendNotification(any()) } just runs
    }
}
