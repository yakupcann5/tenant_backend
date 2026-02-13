package com.aesthetic.backend.controller

import com.aesthetic.backend.config.RequiresModule
import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.ContactMessageResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.usecase.ContactMessageService
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/contact-messages")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
@RequiresModule(FeatureModule.CONTACT_MESSAGES)
class ContactMessageAdminController(
    private val contactMessageService: ContactMessageService
) {

    @GetMapping
    fun list(pageable: Pageable): ResponseEntity<PagedResponse<ContactMessageResponse>> {
        return ResponseEntity.ok(contactMessageService.list(pageable))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<ApiResponse<ContactMessageResponse>> {
        return ResponseEntity.ok(ApiResponse(data = contactMessageService.getById(id)))
    }

    @PatchMapping("/{id}/read")
    fun markAsRead(@PathVariable id: String): ResponseEntity<ApiResponse<ContactMessageResponse>> {
        return ResponseEntity.ok(ApiResponse(data = contactMessageService.markAsRead(id), message = "Okundu olarak işaretlendi"))
    }

    @PatchMapping("/{id}/unread")
    fun markAsUnread(@PathVariable id: String): ResponseEntity<ApiResponse<ContactMessageResponse>> {
        return ResponseEntity.ok(ApiResponse(data = contactMessageService.markAsUnread(id), message = "Okunmadı olarak işaretlendi"))
    }

    @GetMapping("/unread-count")
    fun getUnreadCount(): ResponseEntity<ApiResponse<Long>> {
        return ResponseEntity.ok(ApiResponse(data = contactMessageService.getUnreadCount()))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        contactMessageService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
