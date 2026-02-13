package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.schedule.WorkingHours
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.DayHoursRequest
import com.aesthetic.backend.dto.request.SetWorkingHoursRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.repository.WorkingHoursRepository
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
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.*

@ExtendWith(MockKExtension::class)
class WorkingHoursServiceTest {

    @MockK
    private lateinit var workingHoursRepository: WorkingHoursRepository

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var entityManager: EntityManager

    private lateinit var workingHoursService: WorkingHoursService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        workingHoursService = WorkingHoursService(workingHoursRepository, userRepository, entityManager)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `getFacilityHours should return facility hours`() {
        val hours = listOf(
            createWorkingHours("wh-1", DayOfWeek.MONDAY),
            createWorkingHours("wh-2", DayOfWeek.TUESDAY)
        )
        every { workingHoursRepository.findFacilityHours(tenantId) } returns hours

        val result = workingHoursService.getFacilityHours()

        assertEquals(2, result.size)
        assertEquals(DayOfWeek.MONDAY, result[0].dayOfWeek)
        assertEquals(DayOfWeek.TUESDAY, result[1].dayOfWeek)
    }

    @Test
    fun `setFacilityHours should replace all facility hours`() {
        val existing = listOf(createWorkingHours("wh-1", DayOfWeek.MONDAY))
        every { workingHoursRepository.findFacilityHours(tenantId) } returns existing
        every { workingHoursRepository.deleteAll(existing) } just Runs
        every { workingHoursRepository.flush() } just Runs
        every { workingHoursRepository.saveAll(any<List<WorkingHours>>()) } answers {
            (firstArg() as List<WorkingHours>).mapIndexed { i, wh ->
                wh.apply {
                    val idField = WorkingHours::class.java.getDeclaredField("id")
                    idField.isAccessible = true
                    idField.set(this, "new-wh-$i")
                }
            }
        }

        val request = SetWorkingHoursRequest(
            hours = listOf(
                DayHoursRequest(DayOfWeek.MONDAY, true, LocalTime.of(9, 0), LocalTime.of(18, 0)),
                DayHoursRequest(DayOfWeek.TUESDAY, true, LocalTime.of(10, 0), LocalTime.of(20, 0)),
                DayHoursRequest(DayOfWeek.WEDNESDAY, false)
            )
        )

        val result = workingHoursService.setFacilityHours(request)

        assertEquals(3, result.size)
        verify { workingHoursRepository.deleteAll(existing) }
        verify { workingHoursRepository.saveAll(any<List<WorkingHours>>()) }
    }

    @Test
    fun `setFacilityHours should reject duplicate days`() {
        val request = SetWorkingHoursRequest(
            hours = listOf(
                DayHoursRequest(DayOfWeek.MONDAY, true, LocalTime.of(9, 0), LocalTime.of(18, 0)),
                DayHoursRequest(DayOfWeek.MONDAY, true, LocalTime.of(10, 0), LocalTime.of(20, 0))
            )
        )

        assertThrows<IllegalArgumentException> {
            workingHoursService.setFacilityHours(request)
        }
    }

    @Test
    fun `setFacilityHours should reject endTime before startTime`() {
        val request = SetWorkingHoursRequest(
            hours = listOf(
                DayHoursRequest(DayOfWeek.MONDAY, true, LocalTime.of(18, 0), LocalTime.of(9, 0))
            )
        )

        assertThrows<IllegalArgumentException> {
            workingHoursService.setFacilityHours(request)
        }
    }

    @Test
    fun `setFacilityHours should allow closed days with invalid times`() {
        every { workingHoursRepository.findFacilityHours(tenantId) } returns emptyList()
        every { workingHoursRepository.deleteAll(emptyList()) } just Runs
        every { workingHoursRepository.flush() } just Runs
        every { workingHoursRepository.saveAll(any<List<WorkingHours>>()) } answers {
            (firstArg() as List<WorkingHours>).mapIndexed { i, wh ->
                wh.apply {
                    val idField = WorkingHours::class.java.getDeclaredField("id")
                    idField.isAccessible = true
                    idField.set(this, "new-wh-$i")
                }
            }
        }

        val request = SetWorkingHoursRequest(
            hours = listOf(
                DayHoursRequest(DayOfWeek.SUNDAY, false, LocalTime.of(18, 0), LocalTime.of(9, 0))
            )
        )

        val result = workingHoursService.setFacilityHours(request)

        assertEquals(1, result.size)
        assertFalse(result[0].isOpen)
    }

    @Test
    fun `getStaffHours should return staff hours`() {
        val staffUser = createStaffUser()
        every { userRepository.findById("staff-1") } returns Optional.of(staffUser)
        every { workingHoursRepository.findByStaffId(tenantId, "staff-1") } returns listOf(
            createWorkingHours("wh-1", DayOfWeek.MONDAY)
        )

        val result = workingHoursService.getStaffHours("staff-1")

        assertEquals(1, result.size)
    }

    @Test
    fun `getStaffHours should throw when staff not found`() {
        every { userRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            workingHoursService.getStaffHours("nonexistent")
        }
    }

    @Test
    fun `setStaffHours should replace staff hours`() {
        val staffUser = createStaffUser()
        every { userRepository.findById("staff-1") } returns Optional.of(staffUser)
        every { workingHoursRepository.findByStaffId(tenantId, "staff-1") } returns emptyList()
        every { workingHoursRepository.deleteAll(emptyList()) } just Runs
        every { workingHoursRepository.flush() } just Runs
        val staffRef = mockk<User>()
        every { entityManager.getReference(User::class.java, "staff-1") } returns staffRef
        every { workingHoursRepository.saveAll(any<List<WorkingHours>>()) } answers {
            (firstArg() as List<WorkingHours>).mapIndexed { i, wh ->
                wh.apply {
                    val idField = WorkingHours::class.java.getDeclaredField("id")
                    idField.isAccessible = true
                    idField.set(this, "new-wh-$i")
                }
            }
        }

        val request = SetWorkingHoursRequest(
            hours = listOf(
                DayHoursRequest(DayOfWeek.MONDAY, true, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        val result = workingHoursService.setStaffHours("staff-1", request)

        assertEquals(1, result.size)
        verify { entityManager.getReference(User::class.java, "staff-1") }
    }

    @Test
    fun `setStaffHours should throw when staff not found`() {
        every { userRepository.findById("nonexistent") } returns Optional.empty()

        val request = SetWorkingHoursRequest(
            hours = listOf(
                DayHoursRequest(DayOfWeek.MONDAY, true, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )

        assertThrows<ResourceNotFoundException> {
            workingHoursService.setStaffHours("nonexistent", request)
        }
    }

    private fun createWorkingHours(id: String, day: DayOfWeek) = WorkingHours(
        id = id,
        dayOfWeek = day,
        startTime = LocalTime.of(9, 0),
        endTime = LocalTime.of(18, 0),
        isOpen = true
    ).apply { tenantId = this@WorkingHoursServiceTest.tenantId }

    private fun createStaffUser() = User(
        id = "staff-1",
        firstName = "Test",
        lastName = "Staff",
        email = "staff@test.com"
    ).apply { tenantId = this@WorkingHoursServiceTest.tenantId }
}
