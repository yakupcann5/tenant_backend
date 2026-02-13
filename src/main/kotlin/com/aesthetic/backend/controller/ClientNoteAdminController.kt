package com.aesthetic.backend.controller

import com.aesthetic.backend.config.RequiresModule
import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.dto.request.CreateClientNoteRequest
import com.aesthetic.backend.dto.request.UpdateClientNoteRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.ClientNoteResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.usecase.ClientNoteService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/client-notes")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
@RequiresModule(FeatureModule.CLIENT_NOTES)
class ClientNoteAdminController(
    private val clientNoteService: ClientNoteService
) {

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateClientNoteRequest,
        @AuthenticationPrincipal principal: UserPrincipal
    ): ResponseEntity<ApiResponse<ClientNoteResponse>> {
        val result = clientNoteService.create(request, principal)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(data = result, message = "Not oluşturuldu"))
    }

    @GetMapping
    fun listByClient(@RequestParam clientId: String, pageable: Pageable): ResponseEntity<PagedResponse<ClientNoteResponse>> {
        return ResponseEntity.ok(clientNoteService.listByClient(clientId, pageable))
    }

    @PatchMapping("/{id}")
    fun update(@PathVariable id: String, @Valid @RequestBody request: UpdateClientNoteRequest): ResponseEntity<ApiResponse<ClientNoteResponse>> {
        return ResponseEntity.ok(ApiResponse(data = clientNoteService.update(id, request), message = "Not güncellendi"))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        clientNoteService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
