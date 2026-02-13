package com.aesthetic.backend.usecase

import com.aesthetic.backend.config.JwtProperties
import com.aesthetic.backend.domain.auth.PasswordResetToken
import com.aesthetic.backend.domain.auth.RefreshToken
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.*
import com.aesthetic.backend.exception.AccountLockedException
import com.aesthetic.backend.repository.PasswordResetTokenRepository
import com.aesthetic.backend.repository.RefreshTokenRepository
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.security.JwtTokenProvider
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.tenant.TenantContext
import io.jsonwebtoken.Claims
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Duration
import java.time.Instant
import java.util.*

@ExtendWith(MockKExtension::class)
class AuthServiceTest {

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @MockK
    private lateinit var passwordResetTokenRepository: PasswordResetTokenRepository

    @MockK
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @MockK
    private lateinit var notificationService: NotificationService

    private val passwordEncoder = BCryptPasswordEncoder(12)

    private val jwtProperties = JwtProperties(
        secret = "testSecretKeyThatIsAtLeast256BitsLongForHMACSHA256Algorithm!!",
        accessTokenExpiration = 3600000L,
        refreshTokenExpiration = mapOf(
            "platform-admin" to 86400000L,
            "tenant-admin" to 2592000000L,
            "client" to 5184000000L,
            "staff" to 604800000L
        )
    )

    private lateinit var authService: AuthService

    private val tenantId = "test-tenant-id"
    private val encodedPassword = BCryptPasswordEncoder(12).encode("Test1234")

    @BeforeEach
    fun setUp() {
        authService = AuthService(
            userRepository,
            refreshTokenRepository,
            passwordResetTokenRepository,
            jwtTokenProvider,
            passwordEncoder,
            jwtProperties,
            notificationService
        )
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    // --- LOGIN TESTS ---

    @Test
    fun `login should succeed with valid credentials`() {
        val user = createUser()
        every { userRepository.findByEmailAndTenantId("test@example.com", tenantId) } returns user
        every { userRepository.save(any()) } returns user
        every { jwtTokenProvider.generateAccessToken(any()) } returns "access-token"
        every { jwtTokenProvider.generateRefreshToken(any()) } returns "refresh-token"
        every { jwtTokenProvider.getJtiFromToken("refresh-token") } returns "jti-1"
        every { refreshTokenRepository.save(any()) } returns mockk()

        val result = authService.login(LoginRequest("test@example.com", "Test1234"))

        assertEquals("access-token", result.accessToken)
        assertEquals("refresh-token", result.refreshToken)
        assertEquals("Bearer", result.tokenType)
        assertNotNull(result.user)
        assertEquals("test@example.com", result.user.email)

        verify { userRepository.save(match { it.failedLoginAttempts == 0 && it.lockedUntil == null }) }
    }

    @Test
    fun `login should throw when email not found`() {
        every { userRepository.findByEmailAndTenantId("wrong@example.com", tenantId) } returns null

        val ex = assertThrows<IllegalArgumentException> {
            authService.login(LoginRequest("wrong@example.com", "Test1234"))
        }
        assertEquals("Geçersiz e-posta veya şifre", ex.message)
    }

    @Test
    fun `login should throw when password is wrong and increment failed attempts`() {
        val user = createUser()
        every { userRepository.findByEmailAndTenantId("test@example.com", tenantId) } returns user
        every { userRepository.save(any()) } returns user

        val ex = assertThrows<IllegalArgumentException> {
            authService.login(LoginRequest("test@example.com", "WrongPass1"))
        }
        assertEquals("Geçersiz e-posta veya şifre", ex.message)
        verify { userRepository.save(match { it.failedLoginAttempts == 1 }) }
    }

    @Test
    fun `login should lock account after 5 failed attempts`() {
        val user = createUser().apply { failedLoginAttempts = 4 }
        every { userRepository.findByEmailAndTenantId("test@example.com", tenantId) } returns user
        every { userRepository.save(any()) } returns user

        assertThrows<IllegalArgumentException> {
            authService.login(LoginRequest("test@example.com", "WrongPass1"))
        }

        verify {
            userRepository.save(match {
                it.failedLoginAttempts == 5 && it.lockedUntil != null
            })
        }
    }

    @Test
    fun `login should throw AccountLockedException when account is locked`() {
        val user = createUser().apply {
            lockedUntil = Instant.now().plus(Duration.ofMinutes(10))
        }
        every { userRepository.findByEmailAndTenantId("test@example.com", tenantId) } returns user

        val ex = assertThrows<AccountLockedException> {
            authService.login(LoginRequest("test@example.com", "Test1234"))
        }
        assertTrue(ex.message!!.contains("kilitlendi"))
    }

    @Test
    fun `login should succeed when lock has expired`() {
        val user = createUser().apply {
            lockedUntil = Instant.now().minus(Duration.ofMinutes(1))
            failedLoginAttempts = 5
        }
        every { userRepository.findByEmailAndTenantId("test@example.com", tenantId) } returns user
        every { userRepository.save(any()) } returns user
        every { jwtTokenProvider.generateAccessToken(any()) } returns "access-token"
        every { jwtTokenProvider.generateRefreshToken(any()) } returns "refresh-token"
        every { jwtTokenProvider.getJtiFromToken("refresh-token") } returns "jti-1"
        every { refreshTokenRepository.save(any()) } returns mockk()

        val result = authService.login(LoginRequest("test@example.com", "Test1234"))

        assertNotNull(result)
        verify { userRepository.save(match { it.failedLoginAttempts == 0 && it.lockedUntil == null }) }
    }

    // --- REGISTER TESTS ---

    @Test
    fun `register should create new user and return tokens`() {
        every { userRepository.findByEmailAndTenantId("new@example.com", tenantId) } returns null
        every { userRepository.save(any()) } answers {
            (firstArg() as User).apply {
                // Simulate ID generation and tenant set
                val idField = User::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "new-user-id")
                tenantId = this@AuthServiceTest.tenantId
            }
        }
        every { jwtTokenProvider.generateAccessToken(any()) } returns "access-token"
        every { jwtTokenProvider.generateRefreshToken(any()) } returns "refresh-token"
        every { jwtTokenProvider.getJtiFromToken("refresh-token") } returns "jti-1"
        every { refreshTokenRepository.save(any()) } returns mockk()

        val result = authService.register(
            RegisterRequest("New", "User", "new@example.com", "Test1234", "5551234567")
        )

        assertEquals("access-token", result.accessToken)
        verify {
            userRepository.save(match {
                it.firstName == "New" && it.lastName == "User" && it.email == "new@example.com" && it.role == Role.CLIENT
            })
        }
    }

    @Test
    fun `register should throw when email already exists`() {
        every { userRepository.findByEmailAndTenantId("existing@example.com", tenantId) } returns createUser()

        val ex = assertThrows<IllegalArgumentException> {
            authService.register(
                RegisterRequest("User", "", "existing@example.com", "Test1234")
            )
        }
        assertEquals("Bu e-posta adresi zaten kayıtlı", ex.message)
    }

    // --- REFRESH TOKEN TESTS ---

    @Test
    fun `refreshToken should rotate token with same family`() {
        val refreshJwt = "valid-refresh-jwt"
        val storedToken = RefreshToken(
            id = "old-jti",
            userId = "user-1",
            tenantId = tenantId,
            family = "family-1",
            expiresAt = Instant.now().plus(Duration.ofDays(30))
        )
        val user = createUser()
        val claims = createRefreshClaims("refresh", "old-jti", tenantId)

        every { jwtTokenProvider.validateToken(refreshJwt) } returns true
        every { jwtTokenProvider.getClaims(refreshJwt) } returns claims
        every { refreshTokenRepository.findByIdAndIsRevokedFalse("old-jti") } returns storedToken
        every { refreshTokenRepository.save(any()) } returns mockk()
        every { userRepository.findById("user-1") } returns Optional.of(user)
        every { jwtTokenProvider.generateAccessToken(any()) } returns "new-access-token"
        every { jwtTokenProvider.generateRefreshToken(any()) } returns "new-refresh-token"
        every { jwtTokenProvider.getJtiFromToken("new-refresh-token") } returns "new-jti"

        val result = authService.refreshToken(RefreshTokenRequest(refreshJwt))

        assertEquals("new-access-token", result.accessToken)
        assertEquals("new-refresh-token", result.refreshToken)

        verify { refreshTokenRepository.save(match { it.id == "old-jti" && it.isRevoked }) }
        verify { refreshTokenRepository.save(match { it.family == "family-1" && it.id == "new-jti" }) }
    }

    @Test
    fun `refreshToken should detect theft and revoke entire family`() {
        val refreshJwt = "stolen-refresh-jwt"
        val revokedToken = RefreshToken(
            id = "old-jti",
            userId = "user-1",
            tenantId = tenantId,
            family = "family-1",
            expiresAt = Instant.now().plus(Duration.ofDays(30)),
            isRevoked = true
        )
        val claims = createRefreshClaims("refresh", "old-jti", tenantId)

        every { jwtTokenProvider.validateToken(refreshJwt) } returns true
        every { jwtTokenProvider.getClaims(refreshJwt) } returns claims
        every { refreshTokenRepository.findByIdAndIsRevokedFalse("old-jti") } returns null
        every { refreshTokenRepository.findById("old-jti") } returns Optional.of(revokedToken)
        every { refreshTokenRepository.revokeByFamily("family-1") } returns 3

        val ex = assertThrows<IllegalArgumentException> {
            authService.refreshToken(RefreshTokenRequest(refreshJwt))
        }
        assertEquals("Geçersiz refresh token", ex.message)
        verify { refreshTokenRepository.revokeByFamily("family-1") }
    }

    @Test
    fun `refreshToken should reject non-refresh token type`() {
        val accessJwt = "access-token-jwt"
        val claims = createRefreshClaims(null, "jti-1", tenantId)

        every { jwtTokenProvider.validateToken(accessJwt) } returns true
        every { jwtTokenProvider.getClaims(accessJwt) } returns claims

        assertThrows<IllegalArgumentException> {
            authService.refreshToken(RefreshTokenRequest(accessJwt))
        }
    }

    @Test
    fun `refreshToken should reject expired stored token`() {
        val refreshJwt = "expired-refresh-jwt"
        val storedToken = RefreshToken(
            id = "old-jti",
            userId = "user-1",
            tenantId = tenantId,
            family = "family-1",
            expiresAt = Instant.now().minus(Duration.ofDays(1))
        )
        val claims = createRefreshClaims("refresh", "old-jti", tenantId)

        every { jwtTokenProvider.validateToken(refreshJwt) } returns true
        every { jwtTokenProvider.getClaims(refreshJwt) } returns claims
        every { refreshTokenRepository.findByIdAndIsRevokedFalse("old-jti") } returns storedToken

        assertThrows<IllegalArgumentException> {
            authService.refreshToken(RefreshTokenRequest(refreshJwt))
        }
    }

    // --- FORGOT PASSWORD TESTS ---

    @Test
    fun `forgotPassword should create reset token for existing user`() {
        val user = createUser()
        every { userRepository.findByEmailAndTenantId("test@example.com", tenantId) } returns user
        every { passwordResetTokenRepository.save(any()) } returns mockk()

        authService.forgotPassword(ForgotPasswordRequest("test@example.com"))

        verify {
            passwordResetTokenRepository.save(match {
                it.userId == "user-1" && it.tenantId == tenantId && !it.isUsed
            })
        }
    }

    @Test
    fun `forgotPassword should silently return for non-existing email`() {
        every { userRepository.findByEmailAndTenantId("unknown@example.com", tenantId) } returns null

        authService.forgotPassword(ForgotPasswordRequest("unknown@example.com"))

        verify(exactly = 0) { passwordResetTokenRepository.save(any()) }
    }

    // --- RESET PASSWORD TESTS ---

    @Test
    fun `resetPassword should update password and revoke all tokens`() {
        val user = createUser().apply {
            failedLoginAttempts = 3
            lockedUntil = Instant.now().plus(Duration.ofMinutes(10))
        }
        val resetToken = PasswordResetToken(
            id = "reset-1",
            userId = "user-1",
            tenantId = tenantId,
            token = "reset-uuid",
            expiresAt = Instant.now().plus(Duration.ofMinutes(15))
        )

        every { passwordResetTokenRepository.findByTokenAndIsUsedFalse("reset-uuid") } returns resetToken
        every { userRepository.findById("user-1") } returns Optional.of(user)
        every { userRepository.save(any()) } returns user
        every { passwordResetTokenRepository.save(any()) } returns mockk()
        every { refreshTokenRepository.deleteByUserId("user-1") } just Runs

        authService.resetPassword(ResetPasswordRequest("reset-uuid", "NewPass123"))

        verify {
            userRepository.save(match {
                it.failedLoginAttempts == 0 && it.lockedUntil == null &&
                    passwordEncoder.matches("NewPass123", it.passwordHash)
            })
        }
        verify { passwordResetTokenRepository.save(match { it.isUsed }) }
        verify { refreshTokenRepository.deleteByUserId("user-1") }
    }

    @Test
    fun `resetPassword should throw for invalid token`() {
        every { passwordResetTokenRepository.findByTokenAndIsUsedFalse("bad-token") } returns null

        assertThrows<IllegalArgumentException> {
            authService.resetPassword(ResetPasswordRequest("bad-token", "NewPass123"))
        }
    }

    @Test
    fun `resetPassword should throw for expired token`() {
        val resetToken = PasswordResetToken(
            id = "reset-1",
            userId = "user-1",
            tenantId = tenantId,
            token = "expired-token",
            expiresAt = Instant.now().minus(Duration.ofMinutes(5))
        )

        every { passwordResetTokenRepository.findByTokenAndIsUsedFalse("expired-token") } returns resetToken

        assertThrows<IllegalArgumentException> {
            authService.resetPassword(ResetPasswordRequest("expired-token", "NewPass123"))
        }
    }

    // --- GET CURRENT USER TESTS ---

    @Test
    fun `getCurrentUser should return user response from database`() {
        val user = createUser()
        every { userRepository.findById("user-1") } returns Optional.of(user)

        val principal = UserPrincipal(
            id = "user-1",
            email = "test@example.com",
            tenantId = tenantId,
            role = Role.CLIENT,
            passwordHash = ""
        )

        val result = authService.getCurrentUser(principal)

        assertEquals("user-1", result.id)
        assertEquals("Test", result.firstName)
        assertEquals("User", result.lastName)
        assertEquals("test@example.com", result.email)
        assertEquals(Role.CLIENT, result.role)
    }

    // --- HELPER ---

    private fun createUser() = User(
        id = "user-1",
        firstName = "Test",
        lastName = "User",
        email = "test@example.com",
        passwordHash = encodedPassword,
        role = Role.CLIENT
    ).apply { tenantId = this@AuthServiceTest.tenantId }

    private fun createRefreshClaims(type: String?, jti: String, tenantId: String): Claims {
        val claims = mockk<Claims>()
        every { claims["type"] } returns type
        every { claims.id } returns jti
        every { claims["tenantId"] } returns tenantId
        return claims
    }
}
