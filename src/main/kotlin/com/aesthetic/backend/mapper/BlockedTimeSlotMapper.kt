package com.aesthetic.backend.mapper

import com.aesthetic.backend.domain.schedule.BlockedTimeSlot
import com.aesthetic.backend.dto.response.BlockedTimeSlotResponse

fun BlockedTimeSlot.toResponse(): BlockedTimeSlotResponse = BlockedTimeSlotResponse(
    id = id!!,
    staffId = staff?.id,
    staffName = staff?.let { "${it.firstName} ${it.lastName}".trim() },
    date = date,
    startTime = startTime,
    endTime = endTime,
    reason = reason
)
