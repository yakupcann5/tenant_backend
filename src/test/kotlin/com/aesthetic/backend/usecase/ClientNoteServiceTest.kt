package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.note.ClientNote
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.CreateClientNoteRequest
import com.aesthetic.backend.dto.request.UpdateClientNoteRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.ClientNoteRepository
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
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.util.*

@ExtendWith(MockKExtension::class)
class ClientNoteServiceTest {

    @MockK
    private lateinit var clientNoteRepository: ClientNoteRepository

    @MockK
    private lateinit var entityManager: EntityManager

    private lateinit var clientNoteService: ClientNoteService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        clientNoteService = ClientNoteService(clientNoteRepository, entityManager)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `create should save new note using getReference for client and author`() {
        val clientRef = mockk<User>(relaxed = true)
        every { clientRef.id } returns "client-1"
        every { clientRef.firstName } returns "Mehmet"
        every { clientRef.lastName } returns "Demir"

        val authorRef = mockk<User>(relaxed = true)
        every { authorRef.id } returns "staff-1"
        every { authorRef.firstName } returns "Dr. Ayse"
        every { authorRef.lastName } returns "Yilmaz"

        every { entityManager.getReference(User::class.java, "client-1") } returns clientRef
        every { entityManager.getReference(User::class.java, "staff-1") } returns authorRef
        every { clientNoteRepository.save(any()) } answers {
            (firstArg() as ClientNote).apply {
                val idField = ClientNote::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "note-1")
            }
        }

        val savedNote = ClientNote(
            id = "note-1",
            client = clientRef,
            author = authorRef,
            content = "Patient is allergic to penicillin",
            isPrivate = true
        ).apply { tenantId = this@ClientNoteServiceTest.tenantId }
        every { clientNoteRepository.findByIdWithAuthor("note-1") } returns savedNote

        val principal = UserPrincipal(
            id = "staff-1",
            email = "staff@example.com",
            tenantId = tenantId,
            role = Role.STAFF,
            passwordHash = "hashed"
        )
        val request = CreateClientNoteRequest(
            clientId = "client-1",
            content = "Patient is allergic to penicillin",
            isPrivate = true
        )
        val result = clientNoteService.create(request, principal)

        assertEquals("note-1", result.id)
        assertEquals("client-1", result.clientId)
        assertEquals("staff-1", result.authorId)
        assertEquals("Dr. Ayse Yilmaz", result.authorName)
        assertEquals("Patient is allergic to penicillin", result.content)
        assertTrue(result.isPrivate)

        verify { entityManager.getReference(User::class.java, "client-1") }
        verify { entityManager.getReference(User::class.java, "staff-1") }
    }

    @Test
    fun `update should update provided fields`() {
        val clientUser = createClientUser()
        val authorUser = createStaffUser()
        val note = ClientNote(
            id = "note-1",
            client = clientUser,
            author = authorUser,
            content = "Original content",
            isPrivate = false
        ).apply { tenantId = this@ClientNoteServiceTest.tenantId }

        every { clientNoteRepository.findById("note-1") } returns Optional.of(note)
        every { clientNoteRepository.save(any()) } answers { firstArg() }

        val updatedNote = note.apply {
            content = "Updated content"
            isPrivate = true
        }
        every { clientNoteRepository.findByIdWithAuthor("note-1") } returns updatedNote

        val request = UpdateClientNoteRequest(
            content = "Updated content",
            isPrivate = true
        )
        val result = clientNoteService.update("note-1", request)

        assertEquals("note-1", result.id)
        assertEquals("Updated content", result.content)
        assertTrue(result.isPrivate)
    }

    @Test
    fun `update should throw when not found`() {
        every { clientNoteRepository.findById("nonexistent") } returns Optional.empty()

        val request = UpdateClientNoteRequest(content = "Updated")

        assertThrows<ResourceNotFoundException> {
            clientNoteService.update("nonexistent", request)
        }
    }

    @Test
    fun `delete should remove note`() {
        val note = ClientNote(
            id = "note-1",
            client = createClientUser(),
            author = createStaffUser(),
            content = "Some content"
        ).apply { tenantId = this@ClientNoteServiceTest.tenantId }

        every { clientNoteRepository.findById("note-1") } returns Optional.of(note)
        every { clientNoteRepository.delete(note) } just Runs

        clientNoteService.delete("note-1")

        verify { clientNoteRepository.delete(note) }
    }

    @Test
    fun `delete should throw when not found`() {
        every { clientNoteRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            clientNoteService.delete("nonexistent")
        }
    }

    @Test
    fun `listByClient should return paged response`() {
        val clientUser = createClientUser()
        val authorUser = createStaffUser()
        val notes = listOf(
            ClientNote(
                id = "note-1",
                client = clientUser,
                author = authorUser,
                content = "First note",
                isPrivate = false
            ).apply { tenantId = this@ClientNoteServiceTest.tenantId }
        )
        val pageable = PageRequest.of(0, 20)
        val page = PageImpl(notes, pageable, 1)
        every { clientNoteRepository.findAllByClientIdAndTenantId("client-1", tenantId, pageable) } returns page

        val result = clientNoteService.listByClient("client-1", pageable)

        assertEquals(1, result.data.size)
        assertEquals("note-1", result.data[0].id)
        assertEquals("client-1", result.data[0].clientId)
        assertEquals("First note", result.data[0].content)
        assertEquals(0, result.page)
        assertEquals(20, result.size)
        assertEquals(1, result.totalElements)
    }

    @Test
    fun `listByClient should return empty page when no notes exist`() {
        val pageable = PageRequest.of(0, 20)
        val page = PageImpl<ClientNote>(emptyList(), pageable, 0)
        every { clientNoteRepository.findAllByClientIdAndTenantId("client-1", tenantId, pageable) } returns page

        val result = clientNoteService.listByClient("client-1", pageable)

        assertEquals(0, result.data.size)
        assertEquals(0, result.totalElements)
    }

    private fun createClientUser(id: String = "client-1") = User(
        id = id,
        firstName = "Mehmet",
        lastName = "Demir",
        email = "mehmet@example.com",
        passwordHash = "hashed-password",
        phone = "05551234567",
        role = Role.CLIENT
    ).apply { tenantId = this@ClientNoteServiceTest.tenantId }

    private fun createStaffUser(id: String = "staff-1") = User(
        id = id,
        firstName = "Dr. Ayse",
        lastName = "Yilmaz",
        email = "staff@example.com",
        passwordHash = "hashed-password",
        role = Role.STAFF
    ).apply { tenantId = this@ClientNoteServiceTest.tenantId }
}
