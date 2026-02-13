package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.patient.TreatmentHistory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TreatmentHistoryRepository : JpaRepository<TreatmentHistory, String> {
    fun findAllByClientIdAndTenantId(clientId: String, tenantId: String, pageable: Pageable): Page<TreatmentHistory>

    @Query("""
        SELECT t FROM TreatmentHistory t
        LEFT JOIN FETCH t.performedBy
        WHERE t.id = :id
    """)
    fun findByIdWithPerformedBy(@Param("id") id: String): TreatmentHistory?
}
