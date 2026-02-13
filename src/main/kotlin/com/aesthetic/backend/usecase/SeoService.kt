package com.aesthetic.backend.usecase

import com.aesthetic.backend.dto.request.UpdateSeoRequest
import com.aesthetic.backend.dto.response.SeoResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.BlogPostRepository
import com.aesthetic.backend.repository.ProductRepository
import com.aesthetic.backend.repository.ServiceRepository
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SeoService(
    private val blogPostRepository: BlogPostRepository,
    private val productRepository: ProductRepository,
    private val serviceRepository: ServiceRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun listSeoPages(): List<SeoResponse> {
        val tenantId = TenantContext.getTenantId()
        val result = mutableListOf<SeoResponse>()

        val blogPosts = blogPostRepository.findAllByTenantId(tenantId, org.springframework.data.domain.Pageable.unpaged())
        blogPosts.content.forEach { post ->
            result.add(
                SeoResponse(
                    entityType = "blog-post",
                    entityId = post.id!!,
                    title = post.title,
                    slug = post.slug,
                    seoTitle = post.seoTitle,
                    seoDescription = post.seoDescription,
                    ogImage = post.ogImage
                )
            )
        }

        val products = productRepository.findAllByTenantId(tenantId, org.springframework.data.domain.Pageable.unpaged())
        products.content.forEach { product ->
            result.add(
                SeoResponse(
                    entityType = "product",
                    entityId = product.id!!,
                    title = product.title,
                    slug = product.slug,
                    seoTitle = product.seoTitle,
                    seoDescription = product.seoDescription,
                    ogImage = product.ogImage
                )
            )
        }

        val services = serviceRepository.findAllByTenantId(tenantId, org.springframework.data.domain.Pageable.unpaged())
        services.content.forEach { service ->
            result.add(
                SeoResponse(
                    entityType = "service",
                    entityId = service.id!!,
                    title = service.title,
                    slug = service.slug,
                    seoTitle = service.metaTitle,
                    seoDescription = service.metaDescription,
                    ogImage = service.ogImage
                )
            )
        }

        return result
    }

    @Transactional
    fun updateSeo(entityType: String, entityId: String, request: UpdateSeoRequest): SeoResponse {
        return when (entityType) {
            "blog-post" -> updateBlogPostSeo(entityId, request)
            "product" -> updateProductSeo(entityId, request)
            "service" -> updateServiceSeo(entityId, request)
            else -> throw IllegalArgumentException("Geçersiz entity tipi: $entityType")
        }
    }

    private fun updateBlogPostSeo(id: String, request: UpdateSeoRequest): SeoResponse {
        val post = blogPostRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Blog yazısı bulunamadı: $id") }

        request.seoTitle?.let { post.seoTitle = it }
        request.seoDescription?.let { post.seoDescription = it }
        request.ogImage?.let { post.ogImage = it }

        val saved = blogPostRepository.save(post)
        return SeoResponse(
            entityType = "blog-post",
            entityId = saved.id!!,
            title = saved.title,
            slug = saved.slug,
            seoTitle = saved.seoTitle,
            seoDescription = saved.seoDescription,
            ogImage = saved.ogImage
        )
    }

    private fun updateProductSeo(id: String, request: UpdateSeoRequest): SeoResponse {
        val product = productRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Ürün bulunamadı: $id") }

        request.seoTitle?.let { product.seoTitle = it }
        request.seoDescription?.let { product.seoDescription = it }
        request.ogImage?.let { product.ogImage = it }

        val saved = productRepository.save(product)
        return SeoResponse(
            entityType = "product",
            entityId = saved.id!!,
            title = saved.title,
            slug = saved.slug,
            seoTitle = saved.seoTitle,
            seoDescription = saved.seoDescription,
            ogImage = saved.ogImage
        )
    }

    private fun updateServiceSeo(id: String, request: UpdateSeoRequest): SeoResponse {
        val service = serviceRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Hizmet bulunamadı: $id") }

        request.seoTitle?.let { service.metaTitle = it }
        request.seoDescription?.let { service.metaDescription = it }
        request.ogImage?.let { service.ogImage = it }

        val saved = serviceRepository.save(service)
        return SeoResponse(
            entityType = "service",
            entityId = saved.id!!,
            title = saved.title,
            slug = saved.slug,
            seoTitle = saved.metaTitle,
            seoDescription = saved.metaDescription,
            ogImage = saved.ogImage
        )
    }
}
