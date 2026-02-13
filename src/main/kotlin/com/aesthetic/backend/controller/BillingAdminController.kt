package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.ChangePlanRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.tenant.TenantContext
import com.aesthetic.backend.usecase.PlanLimitService
import com.aesthetic.backend.usecase.SubscriptionService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/billing")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class BillingAdminController(
    private val subscriptionService: SubscriptionService,
    private val planLimitService: PlanLimitService
) {

    @GetMapping
    fun getSubscription(): ResponseEntity<ApiResponse<*>> {
        val result = subscriptionService.getSubscription()
        return ResponseEntity.ok(ApiResponse(data = result, message = "Abonelik bilgileri"))
    }

    @PostMapping("/change-plan")
    fun changePlan(@Valid @RequestBody request: ChangePlanRequest): ResponseEntity<ApiResponse<*>> {
        val result = subscriptionService.changePlan(request)
        return ResponseEntity.ok(ApiResponse(data = result, message = "Plan değişikliği yapıldı"))
    }

    @PostMapping("/cancel")
    fun cancel(): ResponseEntity<ApiResponse<*>> {
        val result = subscriptionService.cancelSubscription()
        return ResponseEntity.ok(ApiResponse(data = result, message = "Abonelik iptal edildi"))
    }

    @GetMapping("/usage")
    fun getUsage(): ResponseEntity<ApiResponse<*>> {
        val tenantId = TenantContext.getTenantId()
        val result = planLimitService.getCurrentUsage(tenantId)
        return ResponseEntity.ok(ApiResponse(data = result, message = "Kullanım bilgileri"))
    }
}
