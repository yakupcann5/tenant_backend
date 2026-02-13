package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.*
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.TokenResponse
import com.aesthetic.backend.dto.response.UserResponse
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.usecase.AuthService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<ApiResponse<TokenResponse>> {
        val tokenResponse = authService.login(request)
        return ResponseEntity.ok(ApiResponse(data = tokenResponse, message = "Giriş başarılı"))
    }

    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<ApiResponse<TokenResponse>> {
        val tokenResponse = authService.register(request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(data = tokenResponse, message = "Kayıt başarılı"))
    }

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshTokenRequest): ResponseEntity<ApiResponse<TokenResponse>> {
        val tokenResponse = authService.refreshToken(request)
        return ResponseEntity.ok(ApiResponse(data = tokenResponse))
    }

    @PostMapping("/forgot-password")
    fun forgotPassword(@Valid @RequestBody request: ForgotPasswordRequest): ResponseEntity<ApiResponse<Nothing>> {
        authService.forgotPassword(request)
        return ResponseEntity.ok(
            ApiResponse(message = "Hesabınız varsa şifre sıfırlama bağlantısı gönderildi")
        )
    }

    @PostMapping("/reset-password")
    fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest): ResponseEntity<ApiResponse<Nothing>> {
        authService.resetPassword(request)
        return ResponseEntity.ok(
            ApiResponse(message = "Şifreniz başarıyla güncellendi")
        )
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: UserPrincipal): ResponseEntity<ApiResponse<UserResponse>> {
        val userResponse = authService.getCurrentUser(principal)
        return ResponseEntity.ok(ApiResponse(data = userResponse))
    }
}
