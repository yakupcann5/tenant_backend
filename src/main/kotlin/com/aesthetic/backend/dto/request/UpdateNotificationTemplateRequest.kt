package com.aesthetic.backend.dto.request

data class UpdateNotificationTemplateRequest(
    val subject: String? = null,
    val body: String? = null,
    val isActive: Boolean? = null
)
