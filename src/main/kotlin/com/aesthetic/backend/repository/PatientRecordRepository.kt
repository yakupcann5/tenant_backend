package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.patient.PatientRecord
import org.springframework.data.jpa.repository.JpaRepository

interface PatientRecordRepository : JpaRepository<PatientRecord, String> {
    fun findByClientIdAndTenantId(clientId: String, tenantId: String): PatientRecord?
}
