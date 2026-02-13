package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.product.Product
import com.aesthetic.backend.dto.response.ProductResponse

fun Product.toResponse(): ProductResponse = ProductResponse(
    id = id!!,
    slug = slug,
    title = title,
    shortDescription = shortDescription,
    description = description,
    price = price,
    currency = currency,
    image = image,
    stockQuantity = stockQuantity,
    isActive = isActive,
    sortOrder = sortOrder,
    features = features.toList(),
    seoTitle = seoTitle,
    seoDescription = seoDescription,
    ogImage = ogImage,
    createdAt = createdAt,
    updatedAt = updatedAt
)
