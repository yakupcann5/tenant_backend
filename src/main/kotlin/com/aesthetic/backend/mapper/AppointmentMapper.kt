package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.appointment.Appointment
import com.aesthetic.backend.domain.appointment.AppointmentServiceEntity
import com.aesthetic.backend.dto.response.AppointmentResponse
import com.aesthetic.backend.dto.response.AppointmentServiceItemResponse
import com.aesthetic.backend.dto.response.AppointmentSummaryResponse

fun Appointment.toResponse(): AppointmentResponse = AppointmentResponse(
    id = id!!,
    clientName = clientName,
    clientEmail = clientEmail,
    clientPhone = clientPhone,
    services = services.sortedBy { it.sortOrder }.map { it.toItemResponse() },
    staffId = staff?.id,
    staffName = staff?.let { "${it.firstName} ${it.lastName}".trim() },
    date = date,
    startTime = startTime,
    endTime = endTime,
    totalDurationMinutes = totalDurationMinutes,
    totalPrice = totalPrice,
    status = status,
    notes = notes,
    recurringGroupId = recurringGroupId,
    recurrenceRule = recurrenceRule,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Appointment.toSummaryResponse(): AppointmentSummaryResponse = AppointmentSummaryResponse(
    id = id!!,
    clientName = clientName,
    primaryServiceName = primaryService?.title,
    staffName = staff?.let { "${it.firstName} ${it.lastName}".trim() },
    date = date,
    startTime = startTime,
    endTime = endTime,
    totalPrice = totalPrice,
    status = status
)

fun AppointmentServiceEntity.toItemResponse(): AppointmentServiceItemResponse =
    AppointmentServiceItemResponse(
        serviceId = service?.id ?: "",
        serviceName = service?.title ?: "",
        price = price,
        durationMinutes = durationMinutes
    )
