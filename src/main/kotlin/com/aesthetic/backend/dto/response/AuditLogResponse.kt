package com.aesthetic.backend.dto.response

import java.time.Instant

data class AuditLogResponse(
    val id: String,
    val userId: String,
    val action: String,
    val entityType: String,
    val entityId: String,
    val details: String?,
    val ipAddress: String?,
    val createdAt: Instant?
)
