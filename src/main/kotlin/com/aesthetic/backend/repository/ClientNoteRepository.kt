package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.note.ClientNote
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ClientNoteRepository : JpaRepository<ClientNote, String> {
    fun findAllByClientIdAndTenantId(clientId: String, tenantId: String, pageable: Pageable): Page<ClientNote>
    fun deleteAllByClientId(clientId: String)

    @Query("""
        SELECT n FROM ClientNote n
        LEFT JOIN FETCH n.author
        WHERE n.id = :id
    """)
    fun findByIdWithAuthor(@Param("id") id: String): ClientNote?
}
