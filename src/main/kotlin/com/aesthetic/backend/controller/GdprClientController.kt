package com.aesthetic.backend.controller

import com.aesthetic.backend.domain.gdpr.ConsentType
import com.aesthetic.backend.dto.request.GrantConsentRequest
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.ConsentRecordResponse
import com.aesthetic.backend.dto.response.UserDataExportResponse
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.usecase.GdprService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/client/gdpr")
@PreAuthorize("hasAuthority('CLIENT')")
class GdprClientController(
    private val gdprService: GdprService
) {

    @GetMapping("/export")
    fun exportData(
        @AuthenticationPrincipal principal: UserPrincipal
    ): ResponseEntity<ApiResponse<UserDataExportResponse>> {
        return ResponseEntity.ok(ApiResponse(data = gdprService.exportUserData(principal.id)))
    }

    @PostMapping("/anonymize")
    fun anonymize(
        @AuthenticationPrincipal principal: UserPrincipal
    ): ResponseEntity<ApiResponse<Nothing>> {
        gdprService.anonymizeUser(principal.id)
        return ResponseEntity.ok(ApiResponse(data = null, message = "Verileriniz anonimleştirildi"))
    }

    @GetMapping("/consents")
    fun getConsents(
        @AuthenticationPrincipal principal: UserPrincipal
    ): ResponseEntity<ApiResponse<List<ConsentRecordResponse>>> {
        return ResponseEntity.ok(ApiResponse(data = gdprService.getConsents(principal.id)))
    }

    @PostMapping("/consents")
    fun grantConsent(
        @AuthenticationPrincipal principal: UserPrincipal,
        @Valid @RequestBody request: GrantConsentRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<ConsentRecordResponse>> {
        val ipAddress = httpRequest.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: httpRequest.remoteAddr
        return ResponseEntity.ok(
            ApiResponse(data = gdprService.grantConsent(principal.id, request, ipAddress), message = "Onay kaydedildi")
        )
    }

    @DeleteMapping("/consents/{type}")
    fun revokeConsent(
        @AuthenticationPrincipal principal: UserPrincipal,
        @PathVariable type: ConsentType
    ): ResponseEntity<ApiResponse<ConsentRecordResponse>> {
        return ResponseEntity.ok(
            ApiResponse(data = gdprService.revokeConsent(principal.id, type), message = "Onay geri alındı")
        )
    }
}
