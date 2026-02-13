package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.product.Product
import com.aesthetic.backend.dto.request.CreateProductRequest
import com.aesthetic.backend.dto.request.UpdateProductRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.ProductRepository
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
import java.math.BigDecimal
import java.util.*

@ExtendWith(MockKExtension::class)
class ProductServiceTest {

    @MockK
    private lateinit var productRepository: ProductRepository

    private lateinit var productService: ProductService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        productService = ProductService(productRepository)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `create should save product with generated slug and features`() {
        every { productRepository.findBySlugAndTenantId("cilt-bakim-kremi", tenantId) } returns null
        every { productRepository.save(any()) } answers {
            (firstArg() as Product).apply {
                val idField = Product::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "prod-1")
            }
        }

        val request = CreateProductRequest(
            title = "Cilt Bakim Kremi",
            description = "Moisturizing cream",
            shortDescription = "Best cream",
            price = BigDecimal("49.90"),
            currency = "TRY",
            stockQuantity = 100,
            features = listOf("Nemlendirici", "Anti-aging")
        )
        val result = productService.create(request)

        assertEquals("prod-1", result.id)
        assertEquals("cilt-bakim-kremi", result.slug)
        assertEquals("Cilt Bakim Kremi", result.title)
        assertEquals("Moisturizing cream", result.description)
        assertEquals("Best cream", result.shortDescription)
        assertEquals(BigDecimal("49.90"), result.price)
        assertEquals("TRY", result.currency)
        assertEquals(100, result.stockQuantity)
        assertEquals(listOf("Nemlendirici", "Anti-aging"), result.features)
        assertTrue(result.isActive)
    }

    @Test
    fun `create should generate unique slug when duplicate exists`() {
        every { productRepository.findBySlugAndTenantId("cilt-bakim-kremi", tenantId) } returns createProduct("existing-1")
        every { productRepository.findBySlugAndTenantId("cilt-bakim-kremi-1", tenantId) } returns null
        every { productRepository.save(any()) } answers {
            (firstArg() as Product).apply {
                val idField = Product::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "prod-2")
            }
        }

        val request = CreateProductRequest(
            title = "Cilt Bakim Kremi",
            description = "Description"
        )
        val result = productService.create(request)

        assertEquals("cilt-bakim-kremi-1", result.slug)
    }

    @Test
    fun `update should update title, slug, and features`() {
        val product = createProduct("prod-1")
        every { productRepository.findById("prod-1") } returns Optional.of(product)
        every { productRepository.findBySlugAndTenantId("updated-product", tenantId) } returns null
        every { productRepository.save(any()) } answers { firstArg() }

        val request = UpdateProductRequest(
            title = "Updated Product",
            features = listOf("Feature A", "Feature B", "Feature C")
        )
        val result = productService.update("prod-1", request)

        assertEquals("Updated Product", result.title)
        assertEquals("updated-product", result.slug)
        assertEquals(listOf("Feature A", "Feature B", "Feature C"), result.features)
    }

    @Test
    fun `update should update only provided fields`() {
        val product = createProduct("prod-1")
        every { productRepository.findById("prod-1") } returns Optional.of(product)
        every { productRepository.save(any()) } answers { firstArg() }

        val request = UpdateProductRequest(
            price = BigDecimal("99.90"),
            isActive = false
        )
        val result = productService.update("prod-1", request)

        assertEquals("Cilt Bakim Kremi", result.title)
        assertEquals(BigDecimal("99.90"), result.price)
        assertFalse(result.isActive)
    }

    @Test
    fun `update should throw when product not found`() {
        every { productRepository.findById("nonexistent") } returns Optional.empty()

        val request = UpdateProductRequest(title = "Updated")

        assertThrows<ResourceNotFoundException> {
            productService.update("nonexistent", request)
        }
    }

    @Test
    fun `delete should remove product`() {
        val product = createProduct("prod-1")
        every { productRepository.findById("prod-1") } returns Optional.of(product)
        every { productRepository.delete(product) } just Runs

        productService.delete("prod-1")

        verify { productRepository.delete(product) }
    }

    @Test
    fun `delete should throw when product not found`() {
        every { productRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            productService.delete("nonexistent")
        }
    }

    @Test
    fun `listActive should return only active products`() {
        val pageable = PageRequest.of(0, 20)
        val activeProduct = createProduct("prod-1")
        val page = PageImpl(listOf(activeProduct), pageable, 1)
        every { productRepository.findAllByTenantIdAndIsActiveTrue(tenantId, pageable) } returns page

        val result = productService.listActive(pageable)

        assertEquals(1, result.data.size)
        assertTrue(result.data[0].isActive)
        assertEquals(0, result.page)
        assertEquals(20, result.size)
        assertEquals(1, result.totalElements)
    }

    @Test
    fun `listAll should return paged response`() {
        val pageable = PageRequest.of(0, 20)
        val products = listOf(createProduct("prod-1"))
        val page = PageImpl(products, pageable, 1)
        every { productRepository.findAllByTenantId(tenantId, pageable) } returns page

        val result = productService.listAll(pageable)

        assertEquals(1, result.data.size)
    }

    @Test
    fun `getById should return product`() {
        val product = createProduct("prod-1")
        every { productRepository.findById("prod-1") } returns Optional.of(product)

        val result = productService.getById("prod-1")

        assertEquals("prod-1", result.id)
        assertEquals("Cilt Bakim Kremi", result.title)
    }

    @Test
    fun `getById should throw when not found`() {
        every { productRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            productService.getById("nonexistent")
        }
    }

    @Test
    fun `getBySlug should return product`() {
        val product = createProduct("prod-1")
        every { productRepository.findBySlugAndTenantId("cilt-bakim-kremi", tenantId) } returns product

        val result = productService.getBySlug("cilt-bakim-kremi")

        assertEquals("prod-1", result.id)
        assertEquals("cilt-bakim-kremi", result.slug)
    }

    @Test
    fun `getBySlug should throw when not found`() {
        every { productRepository.findBySlugAndTenantId("nonexistent", tenantId) } returns null

        assertThrows<ResourceNotFoundException> {
            productService.getBySlug("nonexistent")
        }
    }

    private fun createProduct(id: String = "prod-1"): Product {
        return Product(
            id = id,
            slug = "cilt-bakim-kremi",
            title = "Cilt Bakim Kremi",
            shortDescription = "Best cream",
            description = "Moisturizing cream",
            price = BigDecimal("49.90"),
            currency = "TRY",
            stockQuantity = 100,
            features = mutableListOf("Nemlendirici", "Anti-aging")
        ).apply { tenantId = this@ProductServiceTest.tenantId }
    }
}
