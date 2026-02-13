package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.schedule.BlockedTimeSlot
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.CreateBlockedSlotRequest
import com.aesthetic.backend.dto.response.BlockedTimeSlotResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.BlockedTimeSlotRepository
import com.aesthetic.backend.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BlockedTimeSlotService(
    private val blockedTimeSlotRepository: BlockedTimeSlotRepository,
    private val entityManager: EntityManager
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(request: CreateBlockedSlotRequest): BlockedTimeSlotResponse {
        require(request.endTime.isAfter(request.startTime)) {
            "Bitiş saati başlangıç saatinden sonra olmalıdır"
        }

        val slot = BlockedTimeSlot(
            date = request.date,
            startTime = request.startTime,
            endTime = request.endTime,
            reason = request.reason
        )

        if (request.staffId != null) {
            slot.staff = entityManager.getReference(User::class.java, request.staffId)
        }

        val saved = blockedTimeSlotRepository.save(slot)
        logger.debug("Blocked time slot created: id={}, tenant={}", saved.id, TenantContext.getTenantIdOrNull())
        return saved.toResponse()
    }

    @Transactional
    fun delete(id: String) {
        val slot = blockedTimeSlotRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Blokaj bulunamadı: $id") }
        blockedTimeSlotRepository.delete(slot)
        logger.debug("Blocked time slot deleted: id={}", id)
    }

    @Transactional(readOnly = true)
    fun list(staffId: String?, pageable: Pageable): PagedResponse<BlockedTimeSlotResponse> {
        val tenantId = TenantContext.getTenantId()
        return blockedTimeSlotRepository.findByTenantAndOptionalStaff(tenantId, staffId, pageable)
            .toPagedResponse { it.toResponse() }
    }
}
