package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.audit.AuditLog
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface AuditLogRepository : JpaRepository<AuditLog, String> {
    fun findAllByTenantId(tenantId: String, pageable: Pageable): Page<AuditLog>
    fun findAllByTenantIdAndUserId(tenantId: String, userId: String, pageable: Pageable): Page<AuditLog>
}
