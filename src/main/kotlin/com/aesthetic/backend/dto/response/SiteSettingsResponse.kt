package com.aesthetic.backend.dto.response

import java.time.Instant

data class SiteSettingsResponse(
    val id: String,
    val siteName: String,
    val phone: String,
    val email: String,
    val address: String,
    val whatsapp: String,
    val instagram: String,
    val facebook: String,
    val twitter: String,
    val youtube: String,
    val mapEmbedUrl: String,
    val timezone: String,
    val locale: String,
    val cancellationPolicyHours: Int,
    val defaultSlotDurationMinutes: Int,
    val autoConfirmAppointments: Boolean,
    val themeSettings: String,
    val createdAt: Instant?,
    val updatedAt: Instant?
)
