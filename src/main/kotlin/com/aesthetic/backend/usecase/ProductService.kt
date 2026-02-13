package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.product.Product
import com.aesthetic.backend.dto.request.CreateProductRequest
import com.aesthetic.backend.dto.request.UpdateProductRequest
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.ProductResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.ProductRepository
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer

@Service
class ProductService(
    private val productRepository: ProductRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(request: CreateProductRequest): ProductResponse {
        val tenantId = TenantContext.getTenantId()
        val slug = generateSlug(request.title, tenantId)

        val product = Product(
            slug = slug,
            title = request.title,
            shortDescription = request.shortDescription,
            description = request.description,
            price = request.price,
            currency = request.currency,
            image = request.image,
            stockQuantity = request.stockQuantity,
            sortOrder = request.sortOrder,
            seoTitle = request.seoTitle,
            seoDescription = request.seoDescription,
            ogImage = request.ogImage,
            features = request.features.toMutableList()
        )
        return productRepository.save(product).toResponse()
    }

    @Transactional
    fun update(id: String, request: UpdateProductRequest): ProductResponse {
        val product = productRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Ürün bulunamadı: $id") }

        request.title?.let {
            product.title = it
            product.slug = generateSlug(it, product.tenantId)
        }
        request.shortDescription?.let { product.shortDescription = it }
        request.description?.let { product.description = it }
        request.price?.let { product.price = it }
        request.currency?.let { product.currency = it }
        request.image?.let { product.image = it }
        request.stockQuantity?.let { product.stockQuantity = it }
        request.isActive?.let { product.isActive = it }
        request.sortOrder?.let { product.sortOrder = it }
        request.seoTitle?.let { product.seoTitle = it }
        request.seoDescription?.let { product.seoDescription = it }
        request.ogImage?.let { product.ogImage = it }
        request.features?.let {
            product.features.clear()
            product.features.addAll(it)
        }

        return productRepository.save(product).toResponse()
    }

    @Transactional
    fun delete(id: String) {
        val product = productRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Ürün bulunamadı: $id") }
        productRepository.delete(product)
    }

    @Transactional(readOnly = true)
    fun getById(id: String): ProductResponse {
        return productRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Ürün bulunamadı: $id") }
            .toResponse()
    }

    @Transactional(readOnly = true)
    fun getBySlug(slug: String): ProductResponse {
        val tenantId = TenantContext.getTenantId()
        return productRepository.findBySlugAndTenantId(slug, tenantId)?.toResponse()
            ?: throw ResourceNotFoundException("Ürün bulunamadı: $slug")
    }

    @Transactional(readOnly = true)
    fun listAll(pageable: Pageable): PagedResponse<ProductResponse> {
        val tenantId = TenantContext.getTenantId()
        return productRepository.findAllByTenantId(tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun listActive(pageable: Pageable): PagedResponse<ProductResponse> {
        val tenantId = TenantContext.getTenantId()
        return productRepository.findAllByTenantIdAndIsActiveTrue(tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }

    private fun generateSlug(title: String, tenantId: String): String {
        val base = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace("[^\\p{ASCII}]".toRegex(), "")
            .lowercase()
            .replace("[^a-z0-9]+".toRegex(), "-")
            .trim('-')

        var slug = base
        var counter = 1
        while (productRepository.findBySlugAndTenantId(slug, tenantId) != null) {
            slug = "$base-$counter"
            counter++
        }
        return slug
    }
}
