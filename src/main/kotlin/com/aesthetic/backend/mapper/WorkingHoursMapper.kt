package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.schedule.WorkingHours
import com.aesthetic.backend.dto.response.WorkingHoursResponse

fun WorkingHours.toResponse(): WorkingHoursResponse = WorkingHoursResponse(
    id = id!!,
    dayOfWeek = dayOfWeek,
    startTime = startTime,
    endTime = endTime,
    breakStartTime = breakStartTime,
    breakEndTime = breakEndTime,
    isOpen = isOpen
)
