package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.dto.request.UpdateClientStatusRequest
import com.aesthetic.backend.dto.request.UpdateProfileRequest
import com.aesthetic.backend.dto.response.*
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toClientListResponse
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.mapper.toSummaryResponse
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ClientManagementService(
    private val userRepository: UserRepository,
    private val appointmentRepository: AppointmentRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun listClients(pageable: Pageable): PagedResponse<ClientListResponse> {
        val tenantId = TenantContext.getTenantId()
        return userRepository.findAllByTenantIdAndRole(tenantId, Role.CLIENT, pageable)
            .toPagedResponse { user ->
                val count = appointmentRepository.countByTenantIdAndClientId(tenantId, user.id!!)
                user.toClientListResponse(count)
            }
    }

    @Transactional(readOnly = true)
    fun getClientDetail(id: String): ClientDetailResponse {
        val tenantId = TenantContext.getTenantId()
        val user = userRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Müşteri bulunamadı: $id") }

        val appointmentCount = appointmentRepository.countByTenantIdAndClientId(tenantId, id)
        val lastDate = appointmentRepository.findLastAppointmentDateByClientId(tenantId, id)
        val totalSpent = appointmentRepository.sumTotalSpentByClientId(tenantId, id)

        return ClientDetailResponse(
            id = user.id!!,
            firstName = user.firstName,
            lastName = user.lastName,
            email = user.email,
            phone = user.phone,
            image = user.image,
            isBlacklisted = user.isBlacklisted,
            blacklistReason = user.blacklistReason,
            noShowCount = user.noShowCount,
            appointmentCount = appointmentCount,
            lastAppointmentDate = lastDate,
            totalSpent = totalSpent,
            createdAt = user.createdAt
        )
    }

    @Transactional
    fun updateClientStatus(id: String, request: UpdateClientStatusRequest): ClientDetailResponse {
        val user = userRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Müşteri bulunamadı: $id") }

        user.isBlacklisted = request.isBlacklisted
        if (request.isBlacklisted) {
            user.blacklistedAt = Instant.now()
            user.blacklistReason = request.blacklistReason
        } else {
            user.blacklistedAt = null
            user.blacklistReason = null
        }

        userRepository.save(user)
        return getClientDetail(id)
    }

    @Transactional
    fun removeFromBlacklist(id: String): ClientDetailResponse {
        val user = userRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Müşteri bulunamadı: $id") }

        user.isBlacklisted = false
        user.blacklistedAt = null
        user.blacklistReason = null
        userRepository.save(user)

        logger.info("Client removed from blacklist: userId={}", id)
        return getClientDetail(id)
    }

    @Transactional(readOnly = true)
    fun getAppointmentHistory(clientId: String, pageable: Pageable): PagedResponse<AppointmentSummaryResponse> {
        val tenantId = TenantContext.getTenantId()
        return appointmentRepository.findByClientId(tenantId, clientId, null, pageable)
            .toPagedResponse { it.toSummaryResponse() }
    }

    @Transactional(readOnly = true)
    fun getClientProfile(userId: String): UserResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("Kullanıcı bulunamadı") }
        return user.toResponse()
    }

    @Transactional
    fun updateClientProfile(userId: String, request: UpdateProfileRequest): UserResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("Kullanıcı bulunamadı") }

        request.firstName?.let { user.firstName = it }
        request.lastName?.let { user.lastName = it }
        request.phone?.let { user.phone = it }
        request.image?.let { user.image = it }

        val saved = userRepository.save(user)
        return saved.toResponse()
    }
}
