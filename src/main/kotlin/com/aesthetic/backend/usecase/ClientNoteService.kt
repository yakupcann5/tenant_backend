package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.note.ClientNote
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.CreateClientNoteRequest
import com.aesthetic.backend.dto.request.UpdateClientNoteRequest
import com.aesthetic.backend.dto.response.ClientNoteResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.ClientNoteRepository
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ClientNoteService(
    private val clientNoteRepository: ClientNoteRepository,
    private val entityManager: EntityManager
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(request: CreateClientNoteRequest, principal: UserPrincipal): ClientNoteResponse {
        val note = ClientNote(
            client = entityManager.getReference(User::class.java, request.clientId),
            author = entityManager.getReference(User::class.java, principal.id),
            content = request.content,
            isPrivate = request.isPrivate
        )
        val saved = clientNoteRepository.save(note)
        return clientNoteRepository.findByIdWithAuthor(saved.id!!)!!.toResponse()
    }

    @Transactional
    fun update(id: String, request: UpdateClientNoteRequest): ClientNoteResponse {
        val note = clientNoteRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Not bulunamadı: $id") }

        request.content?.let { note.content = it }
        request.isPrivate?.let { note.isPrivate = it }

        clientNoteRepository.save(note)
        return clientNoteRepository.findByIdWithAuthor(id)!!.toResponse()
    }

    @Transactional
    fun delete(id: String) {
        val note = clientNoteRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Not bulunamadı: $id") }
        clientNoteRepository.delete(note)
    }

    @Transactional(readOnly = true)
    fun listByClient(clientId: String, pageable: Pageable): PagedResponse<ClientNoteResponse> {
        val tenantId = TenantContext.getTenantId()
        return clientNoteRepository.findAllByClientIdAndTenantId(clientId, tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }
}
