package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.AuditLogResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.usecase.AuditLogService
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/audit-logs")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class AuditLogAdminController(
    private val auditLogService: AuditLogService
) {

    @GetMapping
    fun listLogs(pageable: Pageable): ResponseEntity<PagedResponse<AuditLogResponse>> {
        return ResponseEntity.ok(auditLogService.listByTenant(pageable))
    }

    @GetMapping("/user/{userId}")
    fun listByUser(
        @PathVariable userId: String,
        pageable: Pageable
    ): ResponseEntity<PagedResponse<AuditLogResponse>> {
        return ResponseEntity.ok(auditLogService.listByUser(userId, pageable))
    }
}
