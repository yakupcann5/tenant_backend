package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.contact.ContactMessage
import com.aesthetic.backend.dto.request.CreateContactMessageRequest
import com.aesthetic.backend.dto.response.ContactMessageResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.ContactMessageRepository
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ContactMessageService(
    private val contactMessageRepository: ContactMessageRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(request: CreateContactMessageRequest): ContactMessageResponse {
        val message = ContactMessage(
            name = request.name,
            email = request.email,
            phone = request.phone,
            subject = request.subject,
            message = request.message
        )
        return contactMessageRepository.save(message).toResponse()
    }

    @Transactional(readOnly = true)
    fun list(pageable: Pageable): PagedResponse<ContactMessageResponse> {
        val tenantId = TenantContext.getTenantId()
        return contactMessageRepository.findAllByTenantId(tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun getById(id: String): ContactMessageResponse {
        return contactMessageRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Mesaj bulunamadı: $id") }
            .toResponse()
    }

    @Transactional
    fun markAsRead(id: String): ContactMessageResponse {
        val message = contactMessageRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Mesaj bulunamadı: $id") }
        message.isRead = true
        message.readAt = Instant.now()
        return contactMessageRepository.save(message).toResponse()
    }

    @Transactional
    fun markAsUnread(id: String): ContactMessageResponse {
        val message = contactMessageRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Mesaj bulunamadı: $id") }
        message.isRead = false
        message.readAt = null
        return contactMessageRepository.save(message).toResponse()
    }

    @Transactional(readOnly = true)
    fun getUnreadCount(): Long {
        val tenantId = TenantContext.getTenantId()
        return contactMessageRepository.countByTenantIdAndIsReadFalse(tenantId)
    }

    @Transactional
    fun delete(id: String) {
        val message = contactMessageRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Mesaj bulunamadı: $id") }
        contactMessageRepository.delete(message)
    }
}
