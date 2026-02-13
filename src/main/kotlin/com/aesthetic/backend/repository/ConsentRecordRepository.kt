package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.gdpr.ConsentRecord
import com.aesthetic.backend.domain.gdpr.ConsentType
import org.springframework.data.jpa.repository.JpaRepository

interface ConsentRecordRepository : JpaRepository<ConsentRecord, String> {
    fun findAllByUserIdAndTenantId(userId: String, tenantId: String): List<ConsentRecord>
    fun findByUserIdAndTenantIdAndConsentType(userId: String, tenantId: String, consentType: ConsentType): ConsentRecord?
}
