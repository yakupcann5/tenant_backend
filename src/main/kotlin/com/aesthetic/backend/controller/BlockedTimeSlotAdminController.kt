package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.CreateBlockedSlotRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.BlockedTimeSlotResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.usecase.BlockedTimeSlotService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/blocked-slots")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class BlockedTimeSlotAdminController(
    private val blockedTimeSlotService: BlockedTimeSlotService
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) staffId: String?,
        pageable: Pageable
    ): ResponseEntity<PagedResponse<BlockedTimeSlotResponse>> {
        val result = blockedTimeSlotService.list(staffId, pageable)
        return ResponseEntity.ok(result)
    }

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateBlockedSlotRequest
    ): ResponseEntity<ApiResponse<BlockedTimeSlotResponse>> {
        val result = blockedTimeSlotService.create(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse(data = result, message = "Blokaj oluşturuldu")
        )
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        blockedTimeSlotService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
