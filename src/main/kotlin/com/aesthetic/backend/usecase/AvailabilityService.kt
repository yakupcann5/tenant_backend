package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.appointment.AppointmentStatus
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.dto.response.AvailabilityResponse
import com.aesthetic.backend.dto.response.TimeSlotResponse
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.repository.BlockedTimeSlotRepository
import com.aesthetic.backend.repository.ServiceRepository
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.repository.WorkingHoursRepository
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@Service
class AvailabilityService(
    private val workingHoursRepository: WorkingHoursRepository,
    private val appointmentRepository: AppointmentRepository,
    private val blockedTimeSlotRepository: BlockedTimeSlotRepository,
    private val userRepository: UserRepository,
    private val serviceRepository: ServiceRepository,
    private val siteSettingsService: SiteSettingsService
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val excludeStatuses = listOf(AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)

    @Transactional(readOnly = true)
    fun getAvailableSlots(date: LocalDate, serviceIds: List<String>, staffId: String?): List<AvailabilityResponse> {
        val tenantId = TenantContext.getTenantId()
        val settings = siteSettingsService.getSettings()

        val services = serviceRepository.findAllById(serviceIds)
        require(services.size == serviceIds.size) { "Bazı hizmetler bulunamadı" }

        val totalDuration = services.sumOf { it.durationMinutes }
        val slotInterval = settings.defaultSlotDurationMinutes

        if (staffId != null) {
            val staff = userRepository.findById(staffId).orElse(null) ?: return emptyList()
            val staffFullName = "${staff.firstName} ${staff.lastName}".trim()
            val slots = calculateSlotsForStaff(tenantId, staff.id!!, staffFullName, date, totalDuration, slotInterval)
            return if (slots.slots.isNotEmpty()) listOf(slots) else emptyList()
        }

        val activeStaff = userRepository.findByTenantIdAndRoleInAndIsActiveTrue(
            tenantId, listOf(Role.STAFF, Role.TENANT_ADMIN)
        )

        return activeStaff.mapNotNull { staff ->
            val staffFullName = "${staff.firstName} ${staff.lastName}".trim()
            val slots = calculateSlotsForStaff(tenantId, staff.id!!, staffFullName, date, totalDuration, slotInterval)
            if (slots.slots.isNotEmpty()) slots else null
        }
    }

    private fun calculateSlotsForStaff(
        tenantId: String,
        staffId: String,
        staffName: String,
        date: LocalDate,
        totalDuration: Int,
        slotInterval: Int
    ): AvailabilityResponse {
        val dayOfWeek = date.dayOfWeek
        val wh = getWorkingHoursForStaff(tenantId, staffId, dayOfWeek)

        if (wh == null || !wh.isOpen) {
            return AvailabilityResponse(date = date, staffId = staffId, staffName = staffName, slots = emptyList())
        }

        val rawSlots = generateTimeSlots(
            wh.startTime, wh.endTime, totalDuration, slotInterval,
            wh.breakStartTime, wh.breakEndTime
        )

        val appointments = appointmentRepository.findConflicts(
            tenantId, staffId, date, wh.startTime, wh.endTime, excludeStatuses
        )
        val blockedSlots = blockedTimeSlotRepository.findByStaffAndDate(tenantId, staffId, date)

        val slots = rawSlots.map { slot ->
            val slotEnd = slot.second
            val slotStart = slot.first
            val isAvailable = appointments.none { appt ->
                slotStart.isBefore(appt.endTime) && slotEnd.isAfter(appt.startTime)
            } && blockedSlots.none { block ->
                slotStart.isBefore(block.endTime) && slotEnd.isAfter(block.startTime)
            }
            TimeSlotResponse(startTime = slotStart, endTime = slotEnd, isAvailable = isAvailable)
        }

        return AvailabilityResponse(date = date, staffId = staffId, staffName = staffName, slots = slots)
    }

    fun generateTimeSlots(
        start: LocalTime,
        end: LocalTime,
        durationMinutes: Int,
        intervalMinutes: Int,
        breakStart: LocalTime?,
        breakEnd: LocalTime?
    ): List<Pair<LocalTime, LocalTime>> {
        val slots = mutableListOf<Pair<LocalTime, LocalTime>>()
        var current = start

        while (true) {
            val slotEnd = current.plusMinutes(durationMinutes.toLong())
            if (slotEnd.isAfter(end)) break

            if (breakStart != null && breakEnd != null) {
                if (current.isBefore(breakEnd) && slotEnd.isAfter(breakStart)) {
                    current = breakEnd
                    continue
                }
            }

            slots.add(current to slotEnd)
            current = current.plusMinutes(intervalMinutes.toLong())
        }

        return slots
    }

    fun isWithinWorkingHours(tenantId: String, staffId: String, date: LocalDate, startTime: LocalTime, endTime: LocalTime): Boolean {
        val wh = getWorkingHoursForStaff(tenantId, staffId, date.dayOfWeek) ?: return false
        if (!wh.isOpen) return false
        if (startTime.isBefore(wh.startTime) || endTime.isAfter(wh.endTime)) return false

        if (wh.breakStartTime != null && wh.breakEndTime != null) {
            if (startTime.isBefore(wh.breakEndTime) && endTime.isAfter(wh.breakStartTime)) {
                return false
            }
        }

        return true
    }

    fun isTimeSlotBlocked(tenantId: String, staffId: String, date: LocalDate, startTime: LocalTime, endTime: LocalTime): Boolean {
        val blockedSlots = blockedTimeSlotRepository.findByStaffAndDate(tenantId, staffId, date)
        return blockedSlots.any { block ->
            startTime.isBefore(block.endTime) && endTime.isAfter(block.startTime)
        }
    }

    fun findAvailableStaff(tenantId: String, date: LocalDate, startTime: LocalTime, endTime: LocalTime): String? {
        val activeStaff = userRepository.findByTenantIdAndRoleInAndIsActiveTrue(
            tenantId, listOf(Role.STAFF, Role.TENANT_ADMIN)
        )

        for (staff in activeStaff) {
            val staffId = staff.id!!
            if (!isWithinWorkingHours(tenantId, staffId, date, startTime, endTime)) continue
            if (isTimeSlotBlocked(tenantId, staffId, date, startTime, endTime)) continue

            val conflicts = appointmentRepository.findConflicts(
                tenantId, staffId, date, startTime, endTime, excludeStatuses
            )
            if (conflicts.isNotEmpty()) continue

            return staffId
        }

        return null
    }

    private data class WorkingHoursInfo(
        val startTime: LocalTime,
        val endTime: LocalTime,
        val breakStartTime: LocalTime?,
        val breakEndTime: LocalTime?,
        val isOpen: Boolean
    )

    private fun getWorkingHoursForStaff(tenantId: String, staffId: String, dayOfWeek: DayOfWeek): WorkingHoursInfo? {
        val staffHours = workingHoursRepository.findByStaffIdAndDayOfWeek(tenantId, staffId, dayOfWeek)
        if (staffHours != null) {
            return WorkingHoursInfo(
                staffHours.startTime, staffHours.endTime,
                staffHours.breakStartTime, staffHours.breakEndTime,
                staffHours.isOpen
            )
        }

        val facilityHours = workingHoursRepository.findFacilityHoursByDay(tenantId, dayOfWeek)
        if (facilityHours != null) {
            return WorkingHoursInfo(
                facilityHours.startTime, facilityHours.endTime,
                facilityHours.breakStartTime, facilityHours.breakEndTime,
                facilityHours.isOpen
            )
        }

        return null
    }
}
