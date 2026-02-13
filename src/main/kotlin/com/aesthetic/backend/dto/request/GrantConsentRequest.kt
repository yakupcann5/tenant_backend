package com.aesthetic.backend.dto.request

import com.aesthetic.backend.domain.gdpr.ConsentType

data class GrantConsentRequest(
    val consentType: ConsentType
)
