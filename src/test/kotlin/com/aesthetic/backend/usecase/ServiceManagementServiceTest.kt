package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.service.Service
import com.aesthetic.backend.domain.service.ServiceCategory
import com.aesthetic.backend.dto.request.CreateServiceRequest
import com.aesthetic.backend.dto.request.UpdateServiceRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.ServiceRepository
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
import java.math.BigDecimal
import java.util.*

@ExtendWith(MockKExtension::class)
class ServiceManagementServiceTest {

    @MockK
    private lateinit var serviceRepository: ServiceRepository

    @MockK
    private lateinit var entityManager: EntityManager

    private lateinit var serviceManagementService: ServiceManagementService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        serviceManagementService = ServiceManagementService(serviceRepository, entityManager)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `create should save new service`() {
        every { serviceRepository.findBySlugAndTenantId("sac-kesimi", tenantId) } returns null
        every { serviceRepository.save(any()) } answers {
            (firstArg() as Service).apply {
                val idField = Service::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "svc-1")
            }
        }


        val request = CreateServiceRequest(
            title = "Saç Kesimi",
            slug = "sac-kesimi",
            price = BigDecimal("150.00"),
            durationMinutes = 45
        )
        val result = serviceManagementService.create(request)

        assertEquals("Saç Kesimi", result.title)
        assertEquals("sac-kesimi", result.slug)
        assertEquals(BigDecimal("150.00"), result.price)
        assertEquals(45, result.durationMinutes)
    }

    @Test
    fun `create should throw when slug already exists`() {
        every { serviceRepository.findBySlugAndTenantId("sac-kesimi", tenantId) } returns createService()

        val request = CreateServiceRequest(title = "Saç Kesimi", slug = "sac-kesimi")

        assertThrows<IllegalArgumentException> {
            serviceManagementService.create(request)
        }
    }

    @Test
    fun `create should set category using getReference`() {
        every { serviceRepository.findBySlugAndTenantId("sac-kesimi", tenantId) } returns null
        val categoryRef = mockk<ServiceCategory>(relaxed = true)
        every { categoryRef.id } returns "cat-1"
        every { categoryRef.name } returns "Test Category"
        every { entityManager.getReference(ServiceCategory::class.java, "cat-1") } returns categoryRef
        every { serviceRepository.save(any()) } answers {
            (firstArg() as Service).apply {
                val idField = Service::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "svc-1")
            }
        }


        val request = CreateServiceRequest(
            title = "Saç Kesimi",
            slug = "sac-kesimi",
            categoryId = "cat-1"
        )
        serviceManagementService.create(request)

        verify { entityManager.getReference(ServiceCategory::class.java, "cat-1") }
    }

    @Test
    fun `listAll should return paged response`() {
        val pageable = PageRequest.of(0, 20)
        val services = listOf(createService())
        val page = PageImpl(services, pageable, 1)
        every { serviceRepository.findAllByTenantId(tenantId, pageable) } returns page

        val result = serviceManagementService.listAll(pageable)

        assertEquals(1, result.data.size)
        assertEquals(0, result.page)
        assertEquals(20, result.size)
        assertEquals(1, result.totalElements)
    }

    @Test
    fun `listActive should return only active services`() {
        val pageable = PageRequest.of(0, 20)
        val services = listOf(createService())
        val page = PageImpl(services, pageable, 1)
        every { serviceRepository.findActiveByTenantId(tenantId, pageable) } returns page

        val result = serviceManagementService.listActive(pageable)

        assertEquals(1, result.data.size)
        assertTrue(result.data[0].isActive)
    }

    @Test
    fun `getById should return service with category`() {
        every { serviceRepository.findByIdWithCategory("svc-1") } returns createService()

        val result = serviceManagementService.getById("svc-1")

        assertEquals("svc-1", result.id)
        assertEquals("Saç Kesimi", result.title)
    }

    @Test
    fun `getById should throw when not found`() {
        every { serviceRepository.findByIdWithCategory("nonexistent") } returns null

        assertThrows<ResourceNotFoundException> {
            serviceManagementService.getById("nonexistent")
        }
    }

    @Test
    fun `getBySlug should return service`() {
        every { serviceRepository.findBySlugWithCategory("sac-kesimi", tenantId) } returns createService()

        val result = serviceManagementService.getBySlug("sac-kesimi")

        assertEquals("sac-kesimi", result.slug)
    }

    @Test
    fun `getBySlug should throw when not found`() {
        every { serviceRepository.findBySlugWithCategory("nonexistent", tenantId) } returns null

        assertThrows<ResourceNotFoundException> {
            serviceManagementService.getBySlug("nonexistent")
        }
    }

    @Test
    fun `update should update provided fields`() {
        val service = createService()
        every { serviceRepository.findById("svc-1") } returns Optional.of(service)
        every { serviceRepository.save(any()) } answers { firstArg() }

        val request = UpdateServiceRequest(
            title = "Updated Title",
            price = BigDecimal("200.00"),
            isActive = false
        )
        val result = serviceManagementService.update("svc-1", request)

        assertEquals("Updated Title", result.title)
        assertEquals(BigDecimal("200.00"), result.price)
        assertFalse(result.isActive)
    }

    @Test
    fun `update slug should check uniqueness`() {
        val service = createService()
        every { serviceRepository.findById("svc-1") } returns Optional.of(service)
        every { serviceRepository.findBySlugAndTenantId("existing-slug", tenantId) } returns createService("svc-2")

        val request = UpdateServiceRequest(slug = "existing-slug")

        assertThrows<IllegalArgumentException> {
            serviceManagementService.update("svc-1", request)
        }
    }

    @Test
    fun `update should remove category when removeCategoryId is true`() {
        val category = ServiceCategory(id = "cat-1", slug = "cat", name = "Cat")
        val service = createService().apply { this.category = category }
        every { serviceRepository.findById("svc-1") } returns Optional.of(service)
        every { serviceRepository.save(any()) } answers { firstArg() }

        val request = UpdateServiceRequest(removeCategoryId = true)
        serviceManagementService.update("svc-1", request)

        assertNull(service.category)
    }

    @Test
    fun `delete should remove service`() {
        val service = createService()
        every { serviceRepository.findById("svc-1") } returns Optional.of(service)
        every { serviceRepository.delete(service) } just Runs

        serviceManagementService.delete("svc-1")

        verify { serviceRepository.delete(service) }
    }

    @Test
    fun `delete should throw when not found`() {
        every { serviceRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            serviceManagementService.delete("nonexistent")
        }
    }

    private fun createService(id: String = "svc-1") = Service(
        id = id,
        slug = "sac-kesimi",
        title = "Saç Kesimi",
        price = BigDecimal("150.00"),
        durationMinutes = 45
    ).apply { tenantId = this@ServiceManagementServiceTest.tenantId }
}
