package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.contact.ContactMessage
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ContactMessageRepository : JpaRepository<ContactMessage, String> {
    fun findAllByTenantId(tenantId: String, pageable: Pageable): Page<ContactMessage>
    fun countByTenantIdAndIsReadFalse(tenantId: String): Long
}
