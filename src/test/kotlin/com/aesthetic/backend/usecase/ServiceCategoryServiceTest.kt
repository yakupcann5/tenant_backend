package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.service.ServiceCategory
import com.aesthetic.backend.dto.request.CreateServiceCategoryRequest
import com.aesthetic.backend.dto.request.UpdateServiceCategoryRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.ServiceCategoryRepository
import com.aesthetic.backend.repository.ServiceRepository
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
import java.util.*

@ExtendWith(MockKExtension::class)
class ServiceCategoryServiceTest {

    @MockK
    private lateinit var serviceCategoryRepository: ServiceCategoryRepository

    @MockK
    private lateinit var serviceRepository: ServiceRepository

    private lateinit var serviceCategoryService: ServiceCategoryService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        serviceCategoryService = ServiceCategoryService(serviceCategoryRepository, serviceRepository)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `create should save new category`() {
        every { serviceCategoryRepository.findBySlugAndTenantId("sac-bakim", tenantId) } returns null
        every { serviceCategoryRepository.save(any()) } answers {
            (firstArg() as ServiceCategory).apply {
                val idField = ServiceCategory::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "cat-1")
            }
        }

        val request = CreateServiceCategoryRequest(name = "Saç Bakım", slug = "sac-bakim")
        val result = serviceCategoryService.create(request)

        assertEquals("Saç Bakım", result.name)
        assertEquals("sac-bakim", result.slug)
        assertEquals(0, result.serviceCount)
    }

    @Test
    fun `create should throw when slug already exists`() {
        val existing = createCategory()
        every { serviceCategoryRepository.findBySlugAndTenantId("sac-bakim", tenantId) } returns existing

        val request = CreateServiceCategoryRequest(name = "Saç Bakım", slug = "sac-bakim")

        assertThrows<IllegalArgumentException> {
            serviceCategoryService.create(request)
        }
    }

    @Test
    fun `listAll should return categories with service counts`() {
        val categories = listOf(createCategory(), createCategory("cat-2", "Cilt Bakım", "cilt-bakim"))
        every { serviceCategoryRepository.findAllByTenantIdOrdered(tenantId) } returns categories
        every { serviceCategoryRepository.countServicesByCategory(tenantId) } returns listOf(
            arrayOf<Any>("cat-1", 3L),
            arrayOf<Any>("cat-2", 5L)
        )

        val result = serviceCategoryService.listAll()

        assertEquals(2, result.size)
        assertEquals(3, result[0].serviceCount)
        assertEquals(5, result[1].serviceCount)
    }

    @Test
    fun `listActive should return only active categories`() {
        val categories = listOf(createCategory())
        every { serviceCategoryRepository.findActiveByTenantIdOrdered(tenantId) } returns categories
        every { serviceCategoryRepository.countServicesByCategory(tenantId) } returns listOf(
            arrayOf<Any>("cat-1", 2L)
        )

        val result = serviceCategoryService.listActive()

        assertEquals(1, result.size)
        assertTrue(result[0].isActive)
    }

    @Test
    fun `getById should return category`() {
        every { serviceCategoryRepository.findById("cat-1") } returns Optional.of(createCategory())
        every { serviceRepository.countByCategoryId("cat-1") } returns 3

        val result = serviceCategoryService.getById("cat-1")

        assertEquals("cat-1", result.id)
        assertEquals("Saç Bakım", result.name)
    }

    @Test
    fun `getById should throw when not found`() {
        every { serviceCategoryRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            serviceCategoryService.getById("nonexistent")
        }
    }

    @Test
    fun `update should update provided fields`() {
        val category = createCategory()
        every { serviceCategoryRepository.findById("cat-1") } returns Optional.of(category)
        every { serviceCategoryRepository.save(any()) } answers { firstArg() }
        every { serviceRepository.countByCategoryId("cat-1") } returns 0

        val request = UpdateServiceCategoryRequest(name = "Updated Name", sortOrder = 5)
        val result = serviceCategoryService.update("cat-1", request)

        assertEquals("Updated Name", result.name)
        assertEquals(5, result.sortOrder)
        assertEquals("sac-bakim", result.slug) // unchanged
    }

    @Test
    fun `update slug should check uniqueness`() {
        val category = createCategory()
        every { serviceCategoryRepository.findById("cat-1") } returns Optional.of(category)
        every { serviceCategoryRepository.findBySlugAndTenantId("existing-slug", tenantId) } returns createCategory("cat-2", "Other", "existing-slug")

        val request = UpdateServiceCategoryRequest(slug = "existing-slug")

        assertThrows<IllegalArgumentException> {
            serviceCategoryService.update("cat-1", request)
        }
    }

    @Test
    fun `update slug should allow keeping same slug`() {
        val category = createCategory()
        every { serviceCategoryRepository.findById("cat-1") } returns Optional.of(category)
        every { serviceCategoryRepository.save(any()) } answers { firstArg() }
        every { serviceRepository.countByCategoryId("cat-1") } returns 0

        val request = UpdateServiceCategoryRequest(slug = "sac-bakim") // same slug
        val result = serviceCategoryService.update("cat-1", request)

        assertEquals("sac-bakim", result.slug)
        verify(exactly = 0) { serviceCategoryRepository.findBySlugAndTenantId(any(), any()) }
    }

    @Test
    fun `delete should remove category`() {
        val category = createCategory()
        every { serviceCategoryRepository.findById("cat-1") } returns Optional.of(category)
        every { serviceCategoryRepository.delete(category) } just Runs

        serviceCategoryService.delete("cat-1")

        verify { serviceCategoryRepository.delete(category) }
    }

    @Test
    fun `delete should throw when not found`() {
        every { serviceCategoryRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            serviceCategoryService.delete("nonexistent")
        }
    }

    private fun createCategory(
        id: String = "cat-1",
        name: String = "Saç Bakım",
        slug: String = "sac-bakim"
    ) = ServiceCategory(
        id = id,
        slug = slug,
        name = name
    ).apply { tenantId = this@ServiceCategoryServiceTest.tenantId }
}
