package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.appointment.Appointment
import com.aesthetic.backend.domain.appointment.AppointmentStatus
import com.aesthetic.backend.domain.schedule.BlockedTimeSlot
import com.aesthetic.backend.domain.schedule.WorkingHours
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.response.SiteSettingsResponse
import com.aesthetic.backend.repository.*
import com.aesthetic.backend.tenant.TenantContext
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@ExtendWith(MockKExtension::class)
class AvailabilityServiceTest {

    @MockK private lateinit var workingHoursRepository: WorkingHoursRepository
    @MockK private lateinit var appointmentRepository: AppointmentRepository
    @MockK private lateinit var blockedTimeSlotRepository: BlockedTimeSlotRepository
    @MockK private lateinit var userRepository: UserRepository
    @MockK private lateinit var serviceRepository: ServiceRepository
    @MockK private lateinit var siteSettingsService: SiteSettingsService

    private lateinit var availabilityService: AvailabilityService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        availabilityService = AvailabilityService(
            workingHoursRepository, appointmentRepository, blockedTimeSlotRepository,
            userRepository, serviceRepository, siteSettingsService
        )
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    // ============= generateTimeSlots =============

    @Test
    fun `generateTimeSlots should generate correct slots for full day`() {
        val slots = availabilityService.generateTimeSlots(
            start = LocalTime.of(9, 0),
            end = LocalTime.of(18, 0),
            durationMinutes = 30,
            intervalMinutes = 30,
            breakStart = null,
            breakEnd = null
        )
        assertEquals(18, slots.size)
        assertEquals(LocalTime.of(9, 0), slots.first().first)
        assertEquals(LocalTime.of(17, 30), slots.last().first)
        assertEquals(LocalTime.of(18, 0), slots.last().second)
    }

    @Test
    fun `generateTimeSlots should skip break period`() {
        val slots = availabilityService.generateTimeSlots(
            start = LocalTime.of(9, 0),
            end = LocalTime.of(18, 0),
            durationMinutes = 30,
            intervalMinutes = 30,
            breakStart = LocalTime.of(12, 0),
            breakEnd = LocalTime.of(13, 0)
        )

        // No slot should overlap with 12:00-13:00
        slots.forEach { (start, end) ->
            assertFalse(start.isBefore(LocalTime.of(13, 0)) && end.isAfter(LocalTime.of(12, 0)),
                "Slot $start-$end overlaps with break 12:00-13:00")
        }
        assertEquals(16, slots.size) // 18 - 2 break slots
    }

    @Test
    fun `generateTimeSlots with duration longer than interval`() {
        val slots = availabilityService.generateTimeSlots(
            start = LocalTime.of(9, 0),
            end = LocalTime.of(12, 0),
            durationMinutes = 60,
            intervalMinutes = 30,
            breakStart = null,
            breakEnd = null
        )

        // Slots: 9:00-10:00, 9:30-10:30, 10:00-11:00, 10:30-11:30, 11:00-12:00, 11:30 can't fit
        assertEquals(5, slots.size)
        assertEquals(LocalTime.of(9, 0), slots[0].first)
        assertEquals(LocalTime.of(10, 0), slots[0].second)
    }

    @Test
    fun `generateTimeSlots returns empty when duration exceeds working hours`() {
        val slots = availabilityService.generateTimeSlots(
            start = LocalTime.of(9, 0),
            end = LocalTime.of(9, 30),
            durationMinutes = 60,
            intervalMinutes = 30,
            breakStart = null,
            breakEnd = null
        )

        assertTrue(slots.isEmpty())
    }

    // ============= isWithinWorkingHours =============

    @Test
    fun `isWithinWorkingHours returns true for valid time`() {
        val wh = createWorkingHours(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0))
        every { workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, "staff-1", DayOfWeek.MONDAY) } returns wh

        val result = availabilityService.isWithinWorkingHours(
            tenantId, "staff-1", LocalDate.of(2026, 2, 16), // Monday
            LocalTime.of(10, 0), LocalTime.of(11, 0)
        )

        assertTrue(result)
    }

    @Test
    fun `isWithinWorkingHours returns false for closed day`() {
        val wh = createWorkingHours(DayOfWeek.SUNDAY, LocalTime.of(9, 0), LocalTime.of(18, 0), isOpen = false)
        every { workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, "staff-1", DayOfWeek.SUNDAY) } returns wh

        val result = availabilityService.isWithinWorkingHours(
            tenantId, "staff-1", LocalDate.of(2026, 2, 15), // Sunday
            LocalTime.of(10, 0), LocalTime.of(11, 0)
        )

        assertFalse(result)
    }

    @Test
    fun `isWithinWorkingHours returns false when time outside working hours`() {
        val wh = createWorkingHours(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0))
        every { workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, "staff-1", DayOfWeek.MONDAY) } returns wh

        val result = availabilityService.isWithinWorkingHours(
            tenantId, "staff-1", LocalDate.of(2026, 2, 16),
            LocalTime.of(17, 30), LocalTime.of(18, 30) // Extends past closing
        )

        assertFalse(result)
    }

    @Test
    fun `isWithinWorkingHours returns false when overlapping with break`() {
        val wh = createWorkingHours(
            DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0),
            breakStart = LocalTime.of(12, 0), breakEnd = LocalTime.of(13, 0)
        )
        every { workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, "staff-1", DayOfWeek.MONDAY) } returns wh

        val result = availabilityService.isWithinWorkingHours(
            tenantId, "staff-1", LocalDate.of(2026, 2, 16),
            LocalTime.of(11, 30), LocalTime.of(12, 30) // Overlaps with break
        )

        assertFalse(result)
    }

    @Test
    fun `isWithinWorkingHours uses facility fallback when no staff hours`() {
        every { workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, "staff-1", DayOfWeek.MONDAY) } returns null
        val facilityWh = createWorkingHours(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0))
        every { workingHoursRepository.findFacilityHoursByDay(tenantId, DayOfWeek.MONDAY) } returns facilityWh

        val result = availabilityService.isWithinWorkingHours(
            tenantId, "staff-1", LocalDate.of(2026, 2, 16),
            LocalTime.of(10, 0), LocalTime.of(11, 0)
        )

        assertTrue(result)
    }

    @Test
    fun `isWithinWorkingHours returns false when no hours at all`() {
        every { workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, "staff-1", DayOfWeek.MONDAY) } returns null
        every { workingHoursRepository.findFacilityHoursByDay(tenantId, DayOfWeek.MONDAY) } returns null

        val result = availabilityService.isWithinWorkingHours(
            tenantId, "staff-1", LocalDate.of(2026, 2, 16),
            LocalTime.of(10, 0), LocalTime.of(11, 0)
        )

        assertFalse(result)
    }

    // ============= isTimeSlotBlocked =============

    @Test
    fun `isTimeSlotBlocked returns true when blocked`() {
        val block = BlockedTimeSlot(
            id = "block-1",
            date = LocalDate.of(2026, 2, 16),
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0)
        )
        every { blockedTimeSlotRepository.findByStaffAndDate(tenantId, "staff-1", any()) } returns listOf(block)

        val result = availabilityService.isTimeSlotBlocked(
            tenantId, "staff-1", LocalDate.of(2026, 2, 16),
            LocalTime.of(11, 0), LocalTime.of(12, 0)
        )

        assertTrue(result)
    }

    @Test
    fun `isTimeSlotBlocked returns false when not blocked`() {
        every { blockedTimeSlotRepository.findByStaffAndDate(tenantId, "staff-1", any()) } returns emptyList()

        val result = availabilityService.isTimeSlotBlocked(
            tenantId, "staff-1", LocalDate.of(2026, 2, 16),
            LocalTime.of(10, 0), LocalTime.of(11, 0)
        )

        assertFalse(result)
    }

    // ============= findAvailableStaff =============

    @Test
    fun `findAvailableStaff returns first available staff`() {
        val staff1 = createUser("staff-1", "Staff 1", Role.STAFF)
        val staff2 = createUser("staff-2", "Staff 2", Role.STAFF)
        val date = LocalDate.of(2026, 2, 16) // Monday

        every { userRepository.findByTenantIdAndRoleInAndIsActiveTrue(tenantId, any()) } returns listOf(staff1, staff2)

        // Staff 1: within working hours
        val wh1 = createWorkingHours(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0))
        every { workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, "staff-1", DayOfWeek.MONDAY) } returns wh1
        every { blockedTimeSlotRepository.findByStaffAndDate(tenantId, "staff-1", date) } returns emptyList()
        // Staff 1: has conflict
        every { appointmentRepository.findConflicts(tenantId, "staff-1", date, any(), any(), any()) } returns listOf(mockk())

        // Staff 2: within working hours, no conflicts
        val wh2 = createWorkingHours(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0))
        every { workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, "staff-2", DayOfWeek.MONDAY) } returns wh2
        every { blockedTimeSlotRepository.findByStaffAndDate(tenantId, "staff-2", date) } returns emptyList()
        every { appointmentRepository.findConflicts(tenantId, "staff-2", date, any(), any(), any()) } returns emptyList()

        val result = availabilityService.findAvailableStaff(
            tenantId, date, LocalTime.of(10, 0), LocalTime.of(11, 0)
        )

        assertEquals("staff-2", result)
    }

    @Test
    fun `findAvailableStaff returns null when no staff available`() {
        val staff1 = createUser("staff-1", "Staff 1", Role.STAFF)
        val date = LocalDate.of(2026, 2, 16)

        every { userRepository.findByTenantIdAndRoleInAndIsActiveTrue(tenantId, any()) } returns listOf(staff1)
        every { workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, "staff-1", DayOfWeek.MONDAY) } returns null
        every { workingHoursRepository.findFacilityHoursByDay(tenantId, DayOfWeek.MONDAY) } returns null

        val result = availabilityService.findAvailableStaff(
            tenantId, date, LocalTime.of(10, 0), LocalTime.of(11, 0)
        )

        assertNull(result)
    }

    // ============= getAvailableSlots =============

    @Test
    fun `getAvailableSlots returns slots for specific staff`() {
        val staff = createUser("staff-1", "Staff 1", Role.STAFF)
        val service = createService("svc-1", 30)
        val date = LocalDate.of(2026, 2, 16) // Monday

        every { siteSettingsService.getSettings() } returns createSettings()
        every { serviceRepository.findAllById(listOf("svc-1")) } returns listOf(service)
        every { userRepository.findById("staff-1") } returns java.util.Optional.of(staff)

        val wh = createWorkingHours(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0))
        every { workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, "staff-1", DayOfWeek.MONDAY) } returns wh
        every { appointmentRepository.findConflicts(tenantId, "staff-1", date, any(), any(), any()) } returns emptyList()
        every { blockedTimeSlotRepository.findByStaffAndDate(tenantId, "staff-1", date) } returns emptyList()

        val result = availabilityService.getAvailableSlots(date, listOf("svc-1"), "staff-1")

        assertEquals(1, result.size)
        assertEquals("staff-1", result[0].staffId)
        assertTrue(result[0].slots.isNotEmpty())
        assertTrue(result[0].slots.all { it.isAvailable })
    }

    @Test
    fun `getAvailableSlots returns empty for closed day`() {
        val staff = createUser("staff-1", "Staff 1", Role.STAFF)
        val service = createService("svc-1", 30)
        val date = LocalDate.of(2026, 2, 15) // Sunday

        every { siteSettingsService.getSettings() } returns createSettings()
        every { serviceRepository.findAllById(listOf("svc-1")) } returns listOf(service)
        every { userRepository.findById("staff-1") } returns java.util.Optional.of(staff)

        val wh = createWorkingHours(DayOfWeek.SUNDAY, LocalTime.of(9, 0), LocalTime.of(18, 0), isOpen = false)
        every { workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, "staff-1", DayOfWeek.SUNDAY) } returns wh

        val result = availabilityService.getAvailableSlots(date, listOf("svc-1"), "staff-1")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getAvailableSlots marks busy slots as unavailable`() {
        val staff = createUser("staff-1", "Staff 1", Role.STAFF)
        val service = createService("svc-1", 30)
        val date = LocalDate.of(2026, 2, 16)

        every { siteSettingsService.getSettings() } returns createSettings()
        every { serviceRepository.findAllById(listOf("svc-1")) } returns listOf(service)
        every { userRepository.findById("staff-1") } returns java.util.Optional.of(staff)

        val wh = createWorkingHours(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0))
        every { workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, "staff-1", DayOfWeek.MONDAY) } returns wh

        val existingAppt = Appointment(
            id = "appt-1",
            date = date,
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(10, 30),
            status = AppointmentStatus.CONFIRMED
        )
        every { appointmentRepository.findConflicts(tenantId, "staff-1", date, any(), any(), any()) } returns listOf(existingAppt)
        every { blockedTimeSlotRepository.findByStaffAndDate(tenantId, "staff-1", date) } returns emptyList()

        val result = availabilityService.getAvailableSlots(date, listOf("svc-1"), "staff-1")

        assertEquals(1, result.size)
        val slots = result[0].slots
        assertTrue(slots.isNotEmpty())

        // The 10:00-10:30 slot should be unavailable
        val busySlot = slots.find { it.startTime == LocalTime.of(10, 0) }
        assertNotNull(busySlot)
        assertFalse(busySlot!!.isAvailable)
    }

    // ============= Helpers =============

    private fun createWorkingHours(
        day: DayOfWeek,
        start: LocalTime,
        end: LocalTime,
        isOpen: Boolean = true,
        breakStart: LocalTime? = null,
        breakEnd: LocalTime? = null
    ) = WorkingHours(
        id = "wh-${day.name}",
        dayOfWeek = day,
        startTime = start,
        endTime = end,
        breakStartTime = breakStart,
        breakEndTime = breakEnd,
        isOpen = isOpen
    ).apply { tenantId = this@AvailabilityServiceTest.tenantId }

    private fun createUser(id: String, name: String, role: Role) = User(
        id = id,
        firstName = name.substringBefore(' '),
        lastName = name.substringAfter(' ', ""),
        email = "$id@test.com",
        role = role
    ).apply { tenantId = this@AvailabilityServiceTest.tenantId }

    private fun createService(id: String, durationMinutes: Int) = com.aesthetic.backend.domain.service.Service(
        id = id,
        slug = "test-service",
        title = "Test Service",
        durationMinutes = durationMinutes,
        bufferMinutes = 0
    ).apply { tenantId = this@AvailabilityServiceTest.tenantId }

    private fun createSettings() = SiteSettingsResponse(
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
        cancellationPolicyHours = 24,
        defaultSlotDurationMinutes = 30,
        autoConfirmAppointments = false,
        themeSettings = "{}",
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}
