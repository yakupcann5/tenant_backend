package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.schedule.WorkingHours
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.SetWorkingHoursRequest
import com.aesthetic.backend.dto.response.WorkingHoursResponse
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.repository.WorkingHoursRepository
import com.aesthetic.backend.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkingHoursService(
    private val workingHoursRepository: WorkingHoursRepository,
    private val userRepository: UserRepository,
    private val entityManager: EntityManager
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getFacilityHours(): List<WorkingHoursResponse> {
        val tenantId = TenantContext.getTenantId()
        return workingHoursRepository.findFacilityHours(tenantId).map { it.toResponse() }
    }

    @Transactional
    fun setFacilityHours(request: SetWorkingHoursRequest): List<WorkingHoursResponse> {
        val tenantId = TenantContext.getTenantId()

        validateHours(request)

        val existing = workingHoursRepository.findFacilityHours(tenantId)
        workingHoursRepository.deleteAll(existing)
        workingHoursRepository.flush()

        val hours = request.hours.map { dayRequest ->
            WorkingHours(
                dayOfWeek = dayRequest.dayOfWeek,
                startTime = dayRequest.startTime,
                endTime = dayRequest.endTime,
                breakStartTime = dayRequest.breakStartTime,
                breakEndTime = dayRequest.breakEndTime,
                isOpen = dayRequest.isOpen
            )
        }

        val saved = workingHoursRepository.saveAll(hours)
        logger.debug("Facility working hours set for tenant={}", tenantId)
        return saved.map { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun getStaffHours(staffId: String): List<WorkingHoursResponse> {
        val tenantId = TenantContext.getTenantId()
        userRepository.findById(staffId)
            .orElseThrow { ResourceNotFoundException("Personel bulunamadı: $staffId") }
        return workingHoursRepository.findByStaffId(tenantId, staffId).map { it.toResponse() }
    }

    @Transactional
    fun setStaffHours(staffId: String, request: SetWorkingHoursRequest): List<WorkingHoursResponse> {
        val tenantId = TenantContext.getTenantId()

        userRepository.findById(staffId)
            .orElseThrow { ResourceNotFoundException("Personel bulunamadı: $staffId") }

        validateHours(request)

        val existing = workingHoursRepository.findByStaffId(tenantId, staffId)
        workingHoursRepository.deleteAll(existing)
        workingHoursRepository.flush()

        val staffRef = entityManager.getReference(User::class.java, staffId)

        val hours = request.hours.map { dayRequest ->
            WorkingHours(
                staff = staffRef,
                dayOfWeek = dayRequest.dayOfWeek,
                startTime = dayRequest.startTime,
                endTime = dayRequest.endTime,
                breakStartTime = dayRequest.breakStartTime,
                breakEndTime = dayRequest.breakEndTime,
                isOpen = dayRequest.isOpen
            )
        }

        val saved = workingHoursRepository.saveAll(hours)
        logger.debug("Staff working hours set: staffId={}, tenant={}", staffId, tenantId)
        return saved.map { it.toResponse() }
    }

    private fun validateHours(request: SetWorkingHoursRequest) {
        val days = request.hours.map { it.dayOfWeek }
        if (days.size != days.toSet().size) {
            throw IllegalArgumentException("Aynı gün birden fazla kez belirtilemez")
        }

        request.hours.filter { it.isOpen }.forEach { day ->
            if (!day.endTime.isAfter(day.startTime)) {
                throw IllegalArgumentException("${day.dayOfWeek}: Bitiş saati başlangıç saatinden sonra olmalıdır")
            }

            if (day.breakStartTime != null && day.breakEndTime != null) {
                require(day.breakEndTime.isAfter(day.breakStartTime)) {
                    "${day.dayOfWeek}: Mola bitiş saati başlangıçtan sonra olmalıdır"
                }
                require(!day.breakStartTime.isBefore(day.startTime)) {
                    "${day.dayOfWeek}: Mola başlangıcı çalışma saatinden önce olamaz"
                }
                require(!day.breakEndTime.isAfter(day.endTime)) {
                    "${day.dayOfWeek}: Mola bitişi çalışma saatinden sonra olamaz"
                }
            } else if (day.breakStartTime != null || day.breakEndTime != null) {
                throw IllegalArgumentException("${day.dayOfWeek}: Mola başlangıç ve bitiş birlikte belirtilmelidir")
            }
        }
    }
}
