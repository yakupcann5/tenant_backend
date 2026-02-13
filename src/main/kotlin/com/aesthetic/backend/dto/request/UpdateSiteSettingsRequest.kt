package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Email

data class UpdateSiteSettingsRequest(
    val siteName: String? = null,
    val phone: String? = null,

    @field:Email(message = "Geçerli bir e-posta adresi giriniz")
    val email: String? = null,

    val address: String? = null,
    val whatsapp: String? = null,
    val instagram: String? = null,
    val facebook: String? = null,
    val twitter: String? = null,
    val youtube: String? = null,
    val mapEmbedUrl: String? = null,
    val timezone: String? = null,
    val locale: String? = null,

    @field:Min(value = 0, message = "İptal politikası süresi sıfır veya pozitif olmalıdır")
    val cancellationPolicyHours: Int? = null,

    @field:Min(value = 5, message = "Slot süresi en az 5 dakika olmalıdır")
    val defaultSlotDurationMinutes: Int? = null,

    val autoConfirmAppointments: Boolean? = null,
    val themeSettings: String? = null
)
