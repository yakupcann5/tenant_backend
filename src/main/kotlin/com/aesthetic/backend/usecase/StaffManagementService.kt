package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.CreateStaffRequest
import com.aesthetic.backend.dto.request.UpdateStaffRequest
import com.aesthetic.backend.dto.response.PublicStaffResponse
import com.aesthetic.backend.dto.response.StaffResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toPublicStaffResponse
import com.aesthetic.backend.mapper.toStaffResponse
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class StaffManagementService(
    private val userRepository: UserRepository,
    private val planLimitService: PlanLimitService,
    private val passwordEncoder: BCryptPasswordEncoder
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun createStaff(request: CreateStaffRequest): StaffResponse {
        val tenantId = TenantContext.getTenantId()
        planLimitService.checkCanCreateStaff(tenantId)

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
            title = request.title,
            role = Role.STAFF,
            forcePasswordChange = true
        )
        val saved = userRepository.save(user)
        logger.info("Staff created: userId={}, tenantId={}", saved.id, tenantId)
        return saved.toStaffResponse()
    }

    @Transactional
    fun updateStaff(id: String, request: UpdateStaffRequest): StaffResponse {
        val user = findStaffOrThrow(id)

        request.firstName?.let { user.firstName = it }
        request.lastName?.let { user.lastName = it }
        request.phone?.let { user.phone = it }
        request.title?.let { user.title = it }
        request.image?.let { user.image = it }

        return userRepository.save(user).toStaffResponse()
    }

    @Transactional
    fun deactivateStaff(id: String): StaffResponse {
        val user = findStaffOrThrow(id)
        user.isActive = false
        return userRepository.save(user).toStaffResponse()
    }

    @Transactional
    fun activateStaff(id: String): StaffResponse {
        val user = findStaffOrThrow(id)
        user.isActive = true
        return userRepository.save(user).toStaffResponse()
    }

    @Transactional(readOnly = true)
    fun listStaff(): List<StaffResponse> {
        val tenantId = TenantContext.getTenantId()
        return userRepository.findByTenantIdAndRoleIn(tenantId, listOf(Role.STAFF))
            .map { it.toStaffResponse() }
    }

    @Transactional(readOnly = true)
    fun getStaff(id: String): StaffResponse {
        return findStaffOrThrow(id).toStaffResponse()
    }

    @Transactional(readOnly = true)
    fun listPublicStaff(): List<PublicStaffResponse> {
        val tenantId = TenantContext.getTenantId()
        return userRepository.findByTenantIdAndRoleInAndIsActiveTrue(tenantId, listOf(Role.STAFF, Role.TENANT_ADMIN))
            .map { it.toPublicStaffResponse() }
    }

    private fun findStaffOrThrow(id: String): User {
        val user = userRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Personel bulunamadı: $id") }
        if (user.role != Role.STAFF) {
            throw ResourceNotFoundException("Personel bulunamadı: $id")
        }
        return user
    }
}
