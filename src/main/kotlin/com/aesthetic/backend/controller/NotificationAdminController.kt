package com.aesthetic.backend.controller

import com.aesthetic.backend.config.RequiresModule
import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.dto.request.UpdateNotificationTemplateRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.NotificationResponse
import com.aesthetic.backend.dto.response.NotificationTemplateResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.usecase.NotificationService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/notifications")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
@RequiresModule(FeatureModule.NOTIFICATIONS)
class NotificationAdminController(
    private val notificationService: NotificationService
) {

    @GetMapping("/logs")
    fun listLogs(pageable: Pageable): ResponseEntity<PagedResponse<NotificationResponse>> {
        return ResponseEntity.ok(notificationService.listLogs(pageable))
    }

    @GetMapping("/templates")
    fun listTemplates(): ResponseEntity<ApiResponse<List<NotificationTemplateResponse>>> {
        return ResponseEntity.ok(ApiResponse(data = notificationService.listTemplates()))
    }

    @PatchMapping("/templates/{id}")
    fun updateTemplate(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateNotificationTemplateRequest
    ): ResponseEntity<ApiResponse<NotificationTemplateResponse>> {
        return ResponseEntity.ok(ApiResponse(data = notificationService.updateTemplate(id, request), message = "Şablon güncellendi"))
    }
}
