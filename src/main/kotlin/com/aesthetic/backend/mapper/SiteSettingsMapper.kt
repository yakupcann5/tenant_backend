package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.settings.SiteSettings
import com.aesthetic.backend.dto.response.SiteSettingsResponse

fun SiteSettings.toResponse(): SiteSettingsResponse = SiteSettingsResponse(
    id = id!!,
    siteName = siteName,
    phone = phone,
    email = email,
    address = address,
    whatsapp = whatsapp,
    instagram = instagram,
    facebook = facebook,
    twitter = twitter,
    youtube = youtube,
    mapEmbedUrl = mapEmbedUrl,
    timezone = timezone,
    locale = locale,
    cancellationPolicyHours = cancellationPolicyHours,
    defaultSlotDurationMinutes = defaultSlotDurationMinutes,
    autoConfirmAppointments = autoConfirmAppointments,
    themeSettings = themeSettings,
    createdAt = createdAt,
    updatedAt = updatedAt
)
