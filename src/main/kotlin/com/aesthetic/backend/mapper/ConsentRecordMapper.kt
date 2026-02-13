package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.gdpr.ConsentRecord
import com.aesthetic.backend.dto.response.ConsentRecordResponse

fun ConsentRecord.toResponse(): ConsentRecordResponse {
    return ConsentRecordResponse(
        id = id!!,
        consentType = consentType,
        isGranted = isGranted,
        grantedAt = grantedAt,
        revokedAt = revokedAt,
        ipAddress = ipAddress
    )
}
