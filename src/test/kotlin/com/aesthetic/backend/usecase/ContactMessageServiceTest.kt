package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.contact.ContactMessage
import com.aesthetic.backend.dto.request.CreateContactMessageRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.ContactMessageRepository
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
import java.util.*

@ExtendWith(MockKExtension::class)
class ContactMessageServiceTest {

    @MockK
    private lateinit var contactMessageRepository: ContactMessageRepository

    private lateinit var contactMessageService: ContactMessageService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        contactMessageService = ContactMessageService(contactMessageRepository)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `create should save new contact message`() {
        every { contactMessageRepository.save(any()) } answers {
            (firstArg() as ContactMessage).apply {
                val idField = ContactMessage::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "msg-1")
            }
        }

        val request = CreateContactMessageRequest(
            name = "Ali Veli",
            email = "ali@example.com",
            phone = "05551234567",
            subject = "Randevu Bilgisi",
            message = "Randevu almak istiyorum"
        )
        val result = contactMessageService.create(request)

        assertEquals("msg-1", result.id)
        assertEquals("Ali Veli", result.name)
        assertEquals("ali@example.com", result.email)
        assertEquals("05551234567", result.phone)
        assertEquals("Randevu Bilgisi", result.subject)
        assertEquals("Randevu almak istiyorum", result.message)
        assertFalse(result.isRead)
        assertNull(result.readAt)
    }

    @Test
    fun `list should return paged response`() {
        val pageable = PageRequest.of(0, 20)
        val messages = listOf(createContactMessage())
        val page = PageImpl(messages, pageable, 1)
        every { contactMessageRepository.findAllByTenantId(tenantId, pageable) } returns page

        val result = contactMessageService.list(pageable)

        assertEquals(1, result.data.size)
        assertEquals(0, result.page)
        assertEquals(20, result.size)
        assertEquals(1, result.totalElements)
    }

    @Test
    fun `markAsRead should set isRead to true and readAt`() {
        val message = createContactMessage()
        every { contactMessageRepository.findById("msg-1") } returns Optional.of(message)
        every { contactMessageRepository.save(any()) } answers { firstArg() }

        val result = contactMessageService.markAsRead("msg-1")

        assertTrue(result.isRead)
        assertNotNull(result.readAt)
    }

    @Test
    fun `markAsRead should throw when not found`() {
        every { contactMessageRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            contactMessageService.markAsRead("nonexistent")
        }
    }

    @Test
    fun `markAsUnread should set isRead to false and readAt to null`() {
        val message = createContactMessage().apply {
            isRead = true
            readAt = java.time.Instant.now()
        }
        every { contactMessageRepository.findById("msg-1") } returns Optional.of(message)
        every { contactMessageRepository.save(any()) } answers { firstArg() }

        val result = contactMessageService.markAsUnread("msg-1")

        assertFalse(result.isRead)
        assertNull(result.readAt)
    }

    @Test
    fun `markAsUnread should throw when not found`() {
        every { contactMessageRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            contactMessageService.markAsUnread("nonexistent")
        }
    }

    @Test
    fun `getUnreadCount should return count of unread messages`() {
        every { contactMessageRepository.countByTenantIdAndIsReadFalse(tenantId) } returns 5L

        val result = contactMessageService.getUnreadCount()

        assertEquals(5L, result)
    }

    @Test
    fun `getUnreadCount should return zero when no unread messages`() {
        every { contactMessageRepository.countByTenantIdAndIsReadFalse(tenantId) } returns 0L

        val result = contactMessageService.getUnreadCount()

        assertEquals(0L, result)
    }

    @Test
    fun `delete should remove contact message`() {
        val message = createContactMessage()
        every { contactMessageRepository.findById("msg-1") } returns Optional.of(message)
        every { contactMessageRepository.delete(message) } just Runs

        contactMessageService.delete("msg-1")

        verify { contactMessageRepository.delete(message) }
    }

    @Test
    fun `delete should throw when not found`() {
        every { contactMessageRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            contactMessageService.delete("nonexistent")
        }
    }

    private fun createContactMessage(id: String = "msg-1") = ContactMessage(
        id = id,
        name = "Ali Veli",
        email = "ali@example.com",
        phone = "05551234567",
        subject = "Randevu Bilgisi",
        message = "Randevu almak istiyorum"
    ).apply { tenantId = this@ContactMessageServiceTest.tenantId }
}
