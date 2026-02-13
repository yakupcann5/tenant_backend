package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.schedule.BlockedTimeSlot
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.CreateBlockedSlotRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.BlockedTimeSlotRepository
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
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.LocalDate
import java.time.LocalTime
import java.util.*

@ExtendWith(MockKExtension::class)
class BlockedTimeSlotServiceTest {

    @MockK private lateinit var blockedTimeSlotRepository: BlockedTimeSlotRepository
    @MockK private lateinit var entityManager: EntityManager

    private lateinit var service: BlockedTimeSlotService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        service = BlockedTimeSlotService(blockedTimeSlotRepository, entityManager)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `create should create blocked slot successfully`() {
        val request = CreateBlockedSlotRequest(
            date = LocalDate.of(2026, 3, 2),
            startTime = LocalTime.of(12, 0),
            endTime = LocalTime.of(13, 0),
            reason = "Öğle arası"
        )

        every { blockedTimeSlotRepository.save(any()) } answers {
            val slot = firstArg<BlockedTimeSlot>()
            val idField = BlockedTimeSlot::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.set(slot, "bts-1")
            slot
        }

        val result = service.create(request)

        assertNotNull(result.id)
        assertEquals(LocalTime.of(12, 0), result.startTime)
        assertEquals(LocalTime.of(13, 0), result.endTime)
        assertEquals("Öğle arası", result.reason)
    }

    @Test
    fun `create should create blocked slot with staff`() {
        val request = CreateBlockedSlotRequest(
            date = LocalDate.of(2026, 3, 2),
            startTime = LocalTime.of(12, 0),
            endTime = LocalTime.of(13, 0),
            staffId = "staff-1"
        )

        val staffRef = mockk<User>(relaxed = true)
        every { entityManager.getReference(User::class.java, "staff-1") } returns staffRef
        every { blockedTimeSlotRepository.save(any()) } answers {
            val slot = firstArg<BlockedTimeSlot>()
            val idField = BlockedTimeSlot::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.set(slot, "bts-1")
            slot
        }

        val result = service.create(request)

        assertNotNull(result)
        verify { entityManager.getReference(User::class.java, "staff-1") }
    }

    @Test
    fun `create should reject endTime before startTime`() {
        val request = CreateBlockedSlotRequest(
            date = LocalDate.of(2026, 3, 2),
            startTime = LocalTime.of(13, 0),
            endTime = LocalTime.of(12, 0)
        )

        assertThrows<IllegalArgumentException> {
            service.create(request)
        }
    }

    @Test
    fun `delete should delete blocked slot`() {
        val slot = BlockedTimeSlot(
            id = "bts-1",
            date = LocalDate.of(2026, 3, 2),
            startTime = LocalTime.of(12, 0),
            endTime = LocalTime.of(13, 0)
        ).apply { tenantId = this@BlockedTimeSlotServiceTest.tenantId }

        every { blockedTimeSlotRepository.findById("bts-1") } returns Optional.of(slot)
        every { blockedTimeSlotRepository.delete(slot) } just Runs

        service.delete("bts-1")

        verify { blockedTimeSlotRepository.delete(slot) }
    }

    @Test
    fun `delete should throw when not found`() {
        every { blockedTimeSlotRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            service.delete("nonexistent")
        }
    }

    @Test
    fun `list should return paginated results`() {
        val slots = listOf(
            BlockedTimeSlot(
                id = "bts-1",
                date = LocalDate.of(2026, 3, 2),
                startTime = LocalTime.of(12, 0),
                endTime = LocalTime.of(13, 0)
            ).apply { tenantId = this@BlockedTimeSlotServiceTest.tenantId }
        )
        val page = PageImpl(slots)

        every { blockedTimeSlotRepository.findByTenantAndOptionalStaff(tenantId, null, any()) } returns page

        val result = service.list(null, Pageable.unpaged())

        assertEquals(1, result.data.size)
        assertEquals(1L, result.totalElements)
    }
}
