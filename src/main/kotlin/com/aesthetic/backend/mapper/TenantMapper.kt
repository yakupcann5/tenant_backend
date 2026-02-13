package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.tenant.Tenant
import com.aesthetic.backend.dto.response.TenantResponse

fun Tenant.toResponse(): TenantResponse = TenantResponse(
    id = id!!,
    slug = slug,
    name = name,
    businessType = businessType,
    phone = phone,
    email = email,
    plan = plan,
    trialEndDate = trialEndDate,
    isActive = isActive,
    createdAt = createdAt
)
