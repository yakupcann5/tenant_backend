package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.tenant.TenantContext
import com.aesthetic.backend.usecase.FileUploadService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/admin/upload")
@PreAuthorize("hasAuthority('TENANT_ADMIN')")
class FileUploadController(
    private val fileUploadService: FileUploadService
) {

    @PostMapping
    fun upload(@RequestParam("file") file: MultipartFile): ResponseEntity<ApiResponse<Map<String, String>>> {
        val tenantId = TenantContext.getTenantId()
        val url = fileUploadService.upload(file, tenantId)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(data = mapOf("url" to url), message = "Dosya yüklendi"))
    }
}
