package com.aesthetic.backend.dto.response

import com.aesthetic.backend.domain.gdpr.ConsentType
import java.time.Instant

data class ConsentRecordResponse(
    val id: String,
    val consentType: ConsentType,
    val isGranted: Boolean,
    val grantedAt: Instant,
    val revokedAt: Instant?,
    val ipAddress: String?
)
