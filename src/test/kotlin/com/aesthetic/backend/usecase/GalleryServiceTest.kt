package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.gallery.GalleryItem
import com.aesthetic.backend.dto.request.CreateGalleryItemRequest
import com.aesthetic.backend.dto.request.UpdateGalleryItemRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.GalleryItemRepository
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
class GalleryServiceTest {

    @MockK
    private lateinit var galleryItemRepository: GalleryItemRepository

    @MockK
    private lateinit var entityManager: EntityManager

    private lateinit var galleryService: GalleryService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        galleryService = GalleryService(galleryItemRepository, entityManager)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `create should save new gallery item`() {
        every { galleryItemRepository.save(any()) } answers {
            (firstArg() as GalleryItem).apply {
                val idField = GalleryItem::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "gallery-1")
            }
        }

        val request = CreateGalleryItemRequest(
            title = "Before After",
            description = "Treatment result",
            imageUrl = "https://example.com/image.jpg",
            beforeImageUrl = "https://example.com/before.jpg",
            afterImageUrl = "https://example.com/after.jpg",
            sortOrder = 1,
            category = "hair"
        )
        val result = galleryService.create(request)

        assertEquals("gallery-1", result.id)
        assertEquals("Before After", result.title)
        assertEquals("Treatment result", result.description)
        assertEquals("https://example.com/image.jpg", result.imageUrl)
        assertEquals("https://example.com/before.jpg", result.beforeImageUrl)
        assertEquals("https://example.com/after.jpg", result.afterImageUrl)
        assertEquals(1, result.sortOrder)
        assertEquals("hair", result.category)
        assertTrue(result.isActive)
    }

    @Test
    fun `create with serviceId should use entityManager getReference`() {
        val serviceRef = mockk<com.aesthetic.backend.domain.service.Service>(relaxed = true)
        every { serviceRef.id } returns "svc-1"
        every { serviceRef.title } returns "Sac Kesimi"
        every { entityManager.getReference(com.aesthetic.backend.domain.service.Service::class.java, "svc-1") } returns serviceRef
        every { galleryItemRepository.save(any()) } answers {
            (firstArg() as GalleryItem).apply {
                val idField = GalleryItem::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "gallery-1")
            }
        }

        val request = CreateGalleryItemRequest(
            imageUrl = "https://example.com/image.jpg",
            serviceId = "svc-1"
        )
        galleryService.create(request)

        verify { entityManager.getReference(com.aesthetic.backend.domain.service.Service::class.java, "svc-1") }
    }

    @Test
    fun `update should update provided fields`() {
        val item = createGalleryItem()
        every { galleryItemRepository.findById("gallery-1") } returns Optional.of(item)
        every { galleryItemRepository.save(any()) } answers { firstArg() }

        val request = UpdateGalleryItemRequest(
            title = "Updated Title",
            description = "Updated description",
            isActive = false,
            sortOrder = 5,
            category = "skin"
        )
        val result = galleryService.update("gallery-1", request)

        assertEquals("Updated Title", result.title)
        assertEquals("Updated description", result.description)
        assertFalse(result.isActive)
        assertEquals(5, result.sortOrder)
        assertEquals("skin", result.category)
    }

    @Test
    fun `update should throw when not found`() {
        every { galleryItemRepository.findById("nonexistent") } returns Optional.empty()

        val request = UpdateGalleryItemRequest(title = "Updated")

        assertThrows<ResourceNotFoundException> {
            galleryService.update("nonexistent", request)
        }
    }

    @Test
    fun `delete should remove gallery item`() {
        val item = createGalleryItem()
        every { galleryItemRepository.findById("gallery-1") } returns Optional.of(item)
        every { galleryItemRepository.delete(item) } just Runs

        galleryService.delete("gallery-1")

        verify { galleryItemRepository.delete(item) }
    }

    @Test
    fun `delete should throw when not found`() {
        every { galleryItemRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            galleryService.delete("nonexistent")
        }
    }

    @Test
    fun `listActive should return only active gallery items`() {
        val pageable = PageRequest.of(0, 20)
        val items = listOf(createGalleryItem())
        val page = PageImpl(items, pageable, 1)
        every { galleryItemRepository.findAllByTenantIdAndIsActiveTrue(tenantId, pageable) } returns page

        val result = galleryService.listActive(pageable)

        assertEquals(1, result.data.size)
        assertTrue(result.data[0].isActive)
        assertEquals(0, result.page)
        assertEquals(20, result.size)
        assertEquals(1, result.totalElements)
    }

    @Test
    fun `listAll should return paged response`() {
        val pageable = PageRequest.of(0, 20)
        val items = listOf(createGalleryItem())
        val page = PageImpl(items, pageable, 1)
        every { galleryItemRepository.findAllByTenantId(tenantId, pageable) } returns page

        val result = galleryService.listAll(pageable)

        assertEquals(1, result.data.size)
        assertEquals(0, result.page)
        assertEquals(20, result.size)
        assertEquals(1, result.totalElements)
    }

    private fun createGalleryItem(id: String = "gallery-1") = GalleryItem(
        id = id,
        title = "Before After",
        description = "Treatment result",
        imageUrl = "https://example.com/image.jpg",
        beforeImageUrl = "https://example.com/before.jpg",
        afterImageUrl = "https://example.com/after.jpg",
        sortOrder = 0,
        category = "hair"
    ).apply { tenantId = this@GalleryServiceTest.tenantId }
}
