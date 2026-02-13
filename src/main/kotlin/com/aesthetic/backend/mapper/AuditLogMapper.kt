package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.audit.AuditLog
import com.aesthetic.backend.dto.response.AuditLogResponse

fun AuditLog.toResponse(): AuditLogResponse {
    return AuditLogResponse(
        id = id!!,
        userId = userId,
        action = action,
        entityType = entityType,
        entityId = entityId,
        details = details,
        ipAddress = ipAddress,
        createdAt = createdAt
    )
}
