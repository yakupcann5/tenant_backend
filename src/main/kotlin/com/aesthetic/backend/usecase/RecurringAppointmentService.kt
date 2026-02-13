package com.aesthetic.backend.usecase

import com.aesthetic.backend.dto.request.CreateRecurringAppointmentRequest
import com.aesthetic.backend.dto.response.RecurringAppointmentResponse
import com.aesthetic.backend.mapper.toSummaryResponse
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.dto.response.AppointmentSummaryResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.*

@Service
class RecurringAppointmentService(
    private val appointmentManagementService: AppointmentManagementService,
    private val appointmentRepository: AppointmentRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun createRecurring(request: CreateRecurringAppointmentRequest): RecurringAppointmentResponse {
        val rule = request.recurrenceRule
        require(rule in listOf("WEEKLY", "BIWEEKLY", "MONTHLY")) { "Geçersiz tekrar kuralı: $rule" }

        val groupId = UUID.randomUUID().toString()
        val created = mutableListOf<AppointmentSummaryResponse>()
        val skipped = mutableListOf<LocalDate>()

        for (i in 0 until request.count) {
            val currentDate = calculateNextDate(request.baseAppointment.date, rule, i)
            try {
                val singleRequest = request.baseAppointment.copy(date = currentDate)
                val result = appointmentManagementService.createAppointment(singleRequest)
                setRecurringGroupId(result.id, groupId, rule)
                created.add(
                    AppointmentSummaryResponse(
                        id = result.id,
                        clientName = result.clientName,
                        primaryServiceName = result.services.firstOrNull()?.serviceName,
                        staffName = result.staffName,
                        date = result.date,
                        startTime = result.startTime,
                        endTime = result.endTime,
                        totalPrice = result.totalPrice,
                        status = result.status
                    )
                )
            } catch (e: Exception) {
                logger.warn("Tekrarlayan randevu atlandı: {} — {}", currentDate, e.message)
                skipped.add(currentDate)
            }
        }

        return RecurringAppointmentResponse(
            groupId = groupId,
            created = created,
            skippedDates = skipped,
            totalRequested = request.count,
            totalCreated = created.size
        )
    }

    @Transactional
    fun setRecurringGroupId(appointmentId: String, groupId: String, rule: String) {
        val appointment = appointmentRepository.findById(appointmentId).orElseThrow()
        appointment.recurringGroupId = groupId
        appointment.recurrenceRule = rule
        appointmentRepository.save(appointment)
    }

    private fun calculateNextDate(baseDate: LocalDate, rule: String, index: Int): LocalDate =
        when (rule) {
            "WEEKLY" -> baseDate.plusWeeks(index.toLong())
            "BIWEEKLY" -> baseDate.plusWeeks(index.toLong() * 2)
            "MONTHLY" -> baseDate.plusMonths(index.toLong())
            else -> throw IllegalArgumentException("Geçersiz kural: $rule")
        }
}
