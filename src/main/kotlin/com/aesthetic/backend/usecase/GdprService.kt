package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.gdpr.ConsentRecord
import com.aesthetic.backend.domain.gdpr.ConsentType
import com.aesthetic.backend.dto.request.GrantConsentRequest
import com.aesthetic.backend.dto.response.*
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.mapper.toSummaryResponse
import com.aesthetic.backend.repository.*
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*

@Service
class GdprService(
    private val userRepository: UserRepository,
    private val appointmentRepository: AppointmentRepository,
    private val reviewRepository: ReviewRepository,
    private val clientNoteRepository: ClientNoteRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val consentRecordRepository: ConsentRecordRepository,
    private val auditLogService: AuditLogService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun exportUserData(userId: String): UserDataExportResponse {
        val tenantId = TenantContext.getTenantId()
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("Kullanıcı bulunamadı") }

        val appointments = appointmentRepository.findByClientId(
            tenantId, userId, null, PageRequest.of(0, 1000)
        ).content.map { it.toSummaryResponse() }

        val consents = consentRecordRepository.findAllByUserIdAndTenantId(userId, tenantId)
            .map { it.toResponse() }

        auditLogService.log("EXPORT_USER_DATA", "User", userId)

        return UserDataExportResponse(
            user = user.toResponse(),
            appointments = appointments,
            consents = consents
        )
    }

    @Transactional
    fun anonymizeUser(userId: String) {
        val tenantId = TenantContext.getTenantId()

        appointmentRepository.anonymizeByClientId(userId)
        reviewRepository.anonymizeByUserId(userId)
        clientNoteRepository.deleteAllByClientId(userId)
        refreshTokenRepository.deleteByUserId(userId)

        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("Kullanıcı bulunamadı") }

        user.firstName = "Anonim"
        user.lastName = ""
        user.email = "anonymized-${UUID.randomUUID()}@deleted.local"
        user.phone = ""
        user.passwordHash = null
        user.isActive = false
        userRepository.save(user)

        auditLogService.log("ANONYMIZE_USER", "User", userId)
        logger.info("User anonymized: userId={}, tenantId={}", userId, tenantId)
    }

    @Transactional
    fun grantConsent(userId: String, request: GrantConsentRequest, ipAddress: String?): ConsentRecordResponse {
        val tenantId = TenantContext.getTenantId()

        val existing = consentRecordRepository.findByUserIdAndTenantIdAndConsentType(
            userId, tenantId, request.consentType
        )

        if (existing != null) {
            existing.isGranted = true
            existing.grantedAt = Instant.now()
            existing.revokedAt = null
            existing.ipAddress = ipAddress
            return consentRecordRepository.save(existing).toResponse()
        }

        val record = ConsentRecord(
            userId = userId,
            consentType = request.consentType,
            grantedAt = Instant.now(),
            ipAddress = ipAddress,
            isGranted = true
        )
        return consentRecordRepository.save(record).toResponse()
    }

    @Transactional
    fun revokeConsent(userId: String, consentType: ConsentType): ConsentRecordResponse {
        val tenantId = TenantContext.getTenantId()
        val record = consentRecordRepository.findByUserIdAndTenantIdAndConsentType(
            userId, tenantId, consentType
        ) ?: throw ResourceNotFoundException("Onay kaydı bulunamadı")

        record.isGranted = false
        record.revokedAt = Instant.now()
        return consentRecordRepository.save(record).toResponse()
    }

    @Transactional(readOnly = true)
    fun getConsents(userId: String): List<ConsentRecordResponse> {
        val tenantId = TenantContext.getTenantId()
        return consentRecordRepository.findAllByUserIdAndTenantId(userId, tenantId)
            .map { it.toResponse() }
    }
}
