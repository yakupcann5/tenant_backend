package com.aesthetic.backend.usecase

import com.aesthetic.backend.config.JwtProperties
import com.aesthetic.backend.domain.auth.PasswordResetToken
import com.aesthetic.backend.domain.auth.RefreshToken
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.*
import com.aesthetic.backend.dto.response.TokenResponse
import com.aesthetic.backend.dto.response.UserResponse
import com.aesthetic.backend.exception.AccountLockedException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.domain.notification.NotificationType
import com.aesthetic.backend.repository.PasswordResetTokenRepository
import com.aesthetic.backend.repository.RefreshTokenRepository
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.security.JwtTokenProvider
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.*

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val passwordEncoder: BCryptPasswordEncoder,
    private val jwtProperties: JwtProperties,
    private val notificationService: NotificationService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun login(request: LoginRequest): TokenResponse {
        val tenantId = TenantContext.getTenantId()
        val user = userRepository.findByEmailAndTenantId(request.email, tenantId)
            ?: throw IllegalArgumentException("Geçersiz e-posta veya şifre")

        if (user.lockedUntil != null && user.lockedUntil!!.isAfter(Instant.now())) {
            val remaining = Duration.between(Instant.now(), user.lockedUntil)
            throw AccountLockedException(
                "Hesabınız kilitlendi. ${remaining.toMinutes()} dakika sonra tekrar deneyiniz."
            )
        }

        if (user.passwordHash == null || !passwordEncoder.matches(request.password, user.passwordHash)) {
            user.failedLoginAttempts++
            if (user.failedLoginAttempts >= 5) {
                user.lockedUntil = Instant.now().plus(Duration.ofMinutes(15))
                logger.warn("Hesap kilitlendi: userId={}, tenantId={}", user.id, tenantId)
            }
            userRepository.save(user)
            throw IllegalArgumentException("Geçersiz e-posta veya şifre")
        }

        user.failedLoginAttempts = 0
        user.lockedUntil = null
        userRepository.save(user)

        return generateTokenPair(user, UUID.randomUUID().toString())
    }

    @Transactional
    fun register(request: RegisterRequest): TokenResponse {
        val tenantId = TenantContext.getTenantId()
        val existing = userRepository.findByEmailAndTenantId(request.email, tenantId)
        if (existing != null) {
            throw IllegalArgumentException("Bu e-posta adresi zaten kayıtlı")
        }

        val user = User(
            firstName = request.firstName,
            lastName = request.lastName,
            email = request.email,
            passwordHash = passwordEncoder.encode(request.password),
            phone = request.phone,
            role = Role.CLIENT
        )
        val savedUser = userRepository.save(user)

        try {
            val ctx = notificationService.toContext(savedUser, NotificationType.WELCOME)
            notificationService.sendNotification(ctx)
        } catch (e: Exception) {
            logger.error("Welcome notification failed for userId: {}", savedUser.id, e)
        }

        return generateTokenPair(savedUser, UUID.randomUUID().toString())
    }

    @Transactional
    fun refreshToken(request: RefreshTokenRequest): TokenResponse {
        if (!jwtTokenProvider.validateToken(request.refreshToken)) {
            throw IllegalArgumentException("Geçersiz refresh token")
        }

        val claims = jwtTokenProvider.getClaims(request.refreshToken)
        val type = claims["type"] as? String
        if (type != "refresh") {
            throw IllegalArgumentException("Geçersiz token tipi")
        }

        val jti = claims.id
            ?: throw IllegalArgumentException("Geçersiz refresh token")

        val tenantId = claims["tenantId"] as? String
            ?: throw IllegalArgumentException("Geçersiz refresh token")

        TenantContext.setTenantId(tenantId)

        val storedToken = refreshTokenRepository.findByIdAndIsRevokedFalse(jti)
        if (storedToken == null) {
            val revokedToken = refreshTokenRepository.findById(jti).orElse(null)
            if (revokedToken != null) {
                logger.warn(
                    "Refresh token theft detected! family={}, userId={}",
                    revokedToken.family, revokedToken.userId
                )
                refreshTokenRepository.revokeByFamily(revokedToken.family)
            }
            throw IllegalArgumentException("Geçersiz refresh token")
        }

        if (storedToken.expiresAt.isBefore(Instant.now())) {
            throw IllegalArgumentException("Refresh token süresi dolmuş")
        }

        val user = userRepository.findById(storedToken.userId)
            .orElseThrow { IllegalArgumentException("Kullanıcı bulunamadı") }

        storedToken.isRevoked = true
        refreshTokenRepository.save(storedToken)

        return generateTokenPair(user, storedToken.family)
    }

    @Transactional
    fun forgotPassword(request: ForgotPasswordRequest) {
        val tenantId = TenantContext.getTenantId()
        val user = userRepository.findByEmailAndTenantId(request.email, tenantId)
            ?: return // Sessizce dön — user sızdırma

        val token = UUID.randomUUID().toString()
        val resetToken = PasswordResetToken(
            userId = user.id!!,
            tenantId = tenantId,
            token = token,
            expiresAt = Instant.now().plus(Duration.ofMinutes(30))
        )
        passwordResetTokenRepository.save(resetToken)

        try {
            val ctx = notificationService.toContext(user, NotificationType.PASSWORD_RESET).copy(
                variables = mapOf(
                    "clientName" to "${user.firstName} ${user.lastName}".trim(),
                    "email" to user.email,
                    "resetToken" to token
                )
            )
            notificationService.sendNotification(ctx)
        } catch (e: Exception) {
            logger.error("Password reset notification failed for userId: {}", user.id, e)
        }
    }

    @Transactional
    fun resetPassword(request: ResetPasswordRequest) {
        val resetToken = passwordResetTokenRepository.findByTokenAndIsUsedFalse(request.token)
            ?: throw IllegalArgumentException("Geçersiz veya kullanılmış token")

        if (resetToken.expiresAt.isBefore(Instant.now())) {
            throw IllegalArgumentException("Token süresi dolmuş")
        }

        val user = userRepository.findById(resetToken.userId)
            .orElseThrow { IllegalArgumentException("Kullanıcı bulunamadı") }

        user.passwordHash = passwordEncoder.encode(request.newPassword)
        user.failedLoginAttempts = 0
        user.lockedUntil = null
        userRepository.save(user)

        resetToken.isUsed = true
        passwordResetTokenRepository.save(resetToken)

        refreshTokenRepository.deleteByUserId(user.id!!)

        logger.info("Password reset completed for userId={}", user.id)
    }

    @Transactional(readOnly = true)
    fun getCurrentUser(principal: UserPrincipal): UserResponse {
        val user = userRepository.findById(principal.id)
            .orElseThrow { IllegalArgumentException("Kullanıcı bulunamadı") }
        return user.toResponse()
    }

    private fun generateTokenPair(user: User, family: String): TokenResponse {
        val principal = UserPrincipal(
            id = user.id!!,
            email = user.email,
            tenantId = user.tenantId,
            role = user.role,
            passwordHash = user.passwordHash ?: ""
        )

        val accessToken = jwtTokenProvider.generateAccessToken(principal)
        val refreshToken = jwtTokenProvider.generateRefreshToken(principal)

        val jti = jwtTokenProvider.getJtiFromToken(refreshToken)!!
        val refreshExpiration = getRefreshTokenExpiration(user.role)

        val refreshTokenEntity = RefreshToken(
            id = jti,
            userId = user.id!!,
            tenantId = user.tenantId,
            family = family,
            expiresAt = Instant.now().plusMillis(refreshExpiration)
        )
        refreshTokenRepository.save(refreshTokenEntity)

        return TokenResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = jwtProperties.accessTokenExpiration,
            user = user.toResponse()
        )
    }

    private fun getRefreshTokenExpiration(role: Role): Long {
        return when (role) {
            Role.PLATFORM_ADMIN -> jwtProperties.refreshTokenExpiration["platform-admin"] ?: 86400000L
            Role.TENANT_ADMIN -> jwtProperties.refreshTokenExpiration["tenant-admin"] ?: 2592000000L
            Role.CLIENT -> jwtProperties.refreshTokenExpiration["client"] ?: 5184000000L
            Role.STAFF -> jwtProperties.refreshTokenExpiration["staff"] ?: 604800000L
        }
    }
}
