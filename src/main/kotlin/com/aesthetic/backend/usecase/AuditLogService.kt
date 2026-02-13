package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.audit.AuditLog
import com.aesthetic.backend.dto.response.AuditLogResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.AuditLogRepository
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Async
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@Service
class AuditLogService(
    private val auditLogRepository: AuditLogRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Async
    fun log(action: String, entityType: String, entityId: String, details: String? = null, ipAddress: String? = null) {
        try {
            val tenantId = TenantContext.getTenantIdOrNull() ?: return
            val userId = SecurityContextHolder.getContext().authentication?.name ?: "system"
            val ip = ipAddress ?: getCurrentIpAddress()

            val auditLog = AuditLog(
                tenantId = tenantId,
                userId = userId,
                action = action,
                entityType = entityType,
                entityId = entityId,
                details = details,
                ipAddress = ip
            )
            auditLogRepository.save(auditLog)
        } catch (e: Exception) {
            logger.error("Audit log failed: action={}, entityType={}, entityId={}", action, entityType, entityId, e)
        }
    }

    @Transactional(readOnly = true)
    fun listByTenant(pageable: Pageable): PagedResponse<AuditLogResponse> {
        val tenantId = TenantContext.getTenantId()
        return auditLogRepository.findAllByTenantId(tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun listByUser(userId: String, pageable: Pageable): PagedResponse<AuditLogResponse> {
        val tenantId = TenantContext.getTenantId()
        return auditLogRepository.findAllByTenantIdAndUserId(tenantId, userId, pageable)
            .toPagedResponse { it.toResponse() }
    }

    private fun getCurrentIpAddress(): String? {
        return try {
            val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            val request = attributes?.request
            request?.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
                ?: request?.remoteAddr
        } catch (e: Exception) {
            null
        }
    }
}
