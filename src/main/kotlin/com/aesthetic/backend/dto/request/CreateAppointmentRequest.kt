package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.LocalDate
import java.time.LocalTime

data class CreateAppointmentRequest(
    @field:NotNull(message = "Tarih zorunludur")
    val date: LocalDate,

    @field:NotNull(message = "Başlangıç saati zorunludur")
    val startTime: LocalTime,

    @field:NotBlank(message = "Müşteri adı zorunludur")
    val clientName: String,

    @field:Email(message = "Geçerli bir e-posta adresi giriniz")
    val clientEmail: String? = null,

    val clientPhone: String = "",

    @field:NotEmpty(message = "En az bir hizmet seçilmelidir")
    val serviceIds: List<String>,

    val staffId: String? = null,

    val notes: String? = null
)
