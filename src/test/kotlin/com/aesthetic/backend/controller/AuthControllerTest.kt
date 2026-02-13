package com.aesthetic.backend.controller

import com.aesthetic.backend.config.GlobalExceptionHandler
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.dto.request.*
import com.aesthetic.backend.dto.response.TokenResponse
import com.aesthetic.backend.dto.response.UserResponse
import com.aesthetic.backend.exception.AccountLockedException
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.usecase.AuthService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.Runs
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

@ExtendWith(MockKExtension::class)
class AuthControllerTest {

    @MockK
    private lateinit var authService: AuthService

    private lateinit var mockMvc: MockMvc
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private val tokenResponse = TokenResponse(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        expiresIn = 3600000L,
        user = UserResponse(
            id = "user-1",
            firstName = "Test",
            lastName = "User",
            email = "test@example.com",
            role = Role.CLIENT,
            phone = "5551234567",
            image = null,
            createdAt = Instant.now()
        )
    )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(AuthController(authService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    // --- LOGIN ---

    @Test
    fun `POST login should return 200 with token response`() {
        every { authService.login(any()) } returns tokenResponse

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest("test@example.com", "Test1234")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("access-token"))
            .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
            .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.message").value("Giriş başarılı"))
    }

    @Test
    fun `POST login should return 400 for invalid credentials`() {
        every { authService.login(any()) } throws IllegalArgumentException("Geçersiz e-posta veya şifre")

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest("test@example.com", "wrong")))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("Geçersiz e-posta veya şifre"))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `POST login should return 423 for locked account`() {
        every { authService.login(any()) } throws AccountLockedException("Hesabınız kilitlendi. 14 dakika sonra tekrar deneyiniz.")

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest("test@example.com", "Test1234")))
        )
            .andExpect(status().isLocked)
            .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
    }

    @Test
    fun `POST login should return 400 for blank email`() {
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email": "", "password": "Test1234"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details.email").exists())
    }

    // --- REGISTER ---

    @Test
    fun `POST register should return 201 with token response`() {
        every { authService.register(any()) } returns tokenResponse

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RegisterRequest("New", "User", "new@example.com", "Test1234", "5551234567")
                    )
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("access-token"))
            .andExpect(jsonPath("$.message").value("Kayıt başarılı"))
    }

    @Test
    fun `POST register should return 400 for invalid email format`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"firstName": "Test", "email": "invalid-email", "password": "Test1234"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details.email").exists())
    }

    @Test
    fun `POST register should return 400 for weak password`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"firstName": "Test", "email": "test@example.com", "password": "weak"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details.password").exists())
    }

    @Test
    fun `POST register should return 400 for duplicate email`() {
        every { authService.register(any()) } throws IllegalArgumentException("Bu e-posta adresi zaten kayıtlı")

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RegisterRequest("User", "", "existing@example.com", "Test1234")
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Bu e-posta adresi zaten kayıtlı"))
    }

    // --- REFRESH ---

    @Test
    fun `POST refresh should return 200 with new tokens`() {
        every { authService.refreshToken(any()) } returns tokenResponse

        mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RefreshTokenRequest("valid-refresh-token")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").exists())
    }

    @Test
    fun `POST refresh should return 400 for blank token`() {
        mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken": ""}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    // --- FORGOT PASSWORD ---

    @Test
    fun `POST forgot-password should return 200 regardless of email existence`() {
        every { authService.forgotPassword(any()) } just Runs

        mockMvc.perform(
            post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ForgotPasswordRequest("test@example.com")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Hesabınız varsa şifre sıfırlama bağlantısı gönderildi"))
    }

    // --- RESET PASSWORD ---

    @Test
    fun `POST reset-password should return 200 on success`() {
        every { authService.resetPassword(any()) } just Runs

        mockMvc.perform(
            post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ResetPasswordRequest("valid-token", "NewPass123")
                    )
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("Şifreniz başarıyla güncellendi"))
    }

    @Test
    fun `POST reset-password should return 400 for invalid token`() {
        every { authService.resetPassword(any()) } throws IllegalArgumentException("Geçersiz veya kullanılmış token")

        mockMvc.perform(
            post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ResetPasswordRequest("bad-token", "NewPass123")
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Geçersiz veya kullanılmış token"))
    }
}
