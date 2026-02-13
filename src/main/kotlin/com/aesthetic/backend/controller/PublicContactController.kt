package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.CreateContactMessageRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.ContactMessageResponse
import com.aesthetic.backend.usecase.ContactMessageService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/public/contact")
class PublicContactController(
    private val contactMessageService: ContactMessageService
) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateContactMessageRequest): ResponseEntity<ApiResponse<ContactMessageResponse>> {
        val result = contactMessageService.create(request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(data = result, message = "Mesajınız alındı"))
    }
}
