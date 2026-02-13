package com.aesthetic.backend.config

import com.aesthetic.backend.dto.response.ErrorCode
import com.aesthetic.backend.dto.response.ErrorResponse
import com.aesthetic.backend.exception.AccountLockedException
import com.aesthetic.backend.exception.AppointmentConflictException
import com.aesthetic.backend.exception.ClientBlacklistedException
import com.aesthetic.backend.exception.NotificationDeliveryException
import com.aesthetic.backend.exception.PlanLimitExceededException
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.exception.TenantNotFoundException
import com.aesthetic.backend.exception.UnauthorizedException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "Geçersiz değer") }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(
                error = "Doğrulama hatası",
                code = ErrorCode.VALIDATION_ERROR,
                details = details
            )
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(
                error = ex.message ?: "Geçersiz istek",
                code = ErrorCode.VALIDATION_ERROR
            )
        )
    }

    @ExceptionHandler(AppointmentConflictException::class)
    fun handleAppointmentConflict(ex: AppointmentConflictException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                error = ex.message ?: "Randevu çakışması",
                code = ErrorCode.APPOINTMENT_CONFLICT
            )
        )
    }

    @ExceptionHandler(ClientBlacklistedException::class)
    fun handleClientBlacklisted(ex: ClientBlacklistedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse(
                error = ex.message ?: "Müşteri kara listede",
                code = ErrorCode.CLIENT_BLACKLISTED
            )
        )
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(ex: AuthenticationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ErrorResponse(
                error = ex.message ?: "Kimlik doğrulama başarısız",
                code = ErrorCode.INVALID_CREDENTIALS
            )
        )
    }

    @ExceptionHandler(PlanLimitExceededException::class)
    fun handlePlanLimitExceeded(ex: PlanLimitExceededException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse(
                error = ex.message ?: "Plan limiti aşıldı",
                code = ErrorCode.PLAN_LIMIT_EXCEEDED
            )
        )
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse(
                error = "Bu işlem için yetkiniz bulunmamaktadır",
                code = ErrorCode.FORBIDDEN
            )
        )
    }

    @ExceptionHandler(AccountLockedException::class)
    fun handleAccountLocked(ex: AccountLockedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.LOCKED).body(
            ErrorResponse(
                error = ex.message ?: "Hesap kilitlendi",
                code = ErrorCode.ACCOUNT_LOCKED
            )
        )
    }

    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleResourceNotFound(ex: ResourceNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(
                error = ex.message ?: "Kaynak bulunamadı",
                code = ErrorCode.RESOURCE_NOT_FOUND
            )
        )
    }

    @ExceptionHandler(TenantNotFoundException::class)
    fun handleTenantNotFound(ex: TenantNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(
                error = ex.message ?: "Tenant bulunamadı",
                code = ErrorCode.TENANT_NOT_FOUND
            )
        )
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(ex: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        logger.warn("Data integrity violation: {}", ex.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                error = "Bu kayıt zaten mevcut",
                code = ErrorCode.DUPLICATE_RESOURCE
            )
        )
    }

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorized(ex: UnauthorizedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ErrorResponse(
                error = ex.message ?: "Kimlik doğrulama gerekli",
                code = ErrorCode.INVALID_CREDENTIALS
            )
        )
    }

    @ExceptionHandler(NotificationDeliveryException::class)
    fun handleNotificationDelivery(ex: NotificationDeliveryException): ResponseEntity<ErrorResponse> {
        logger.error("Bildirim gönderim hatası: {}", ex.message, ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(
                error = ex.message ?: "Bildirim gönderilemedi",
                code = ErrorCode.NOTIFICATION_DELIVERY_FAILED
            )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneral(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Beklenmeyen hata: {}", ex.message, ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(
                error = "Beklenmeyen bir hata oluştu",
                code = ErrorCode.INTERNAL_ERROR
            )
        )
    }
}
