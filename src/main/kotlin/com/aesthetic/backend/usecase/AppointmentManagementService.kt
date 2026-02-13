package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.appointment.Appointment
import com.aesthetic.backend.domain.appointment.AppointmentServiceEntity
import com.aesthetic.backend.domain.appointment.AppointmentStatus
import com.aesthetic.backend.domain.service.Service
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.CreateAppointmentRequest
import com.aesthetic.backend.dto.request.RescheduleAppointmentRequest
import com.aesthetic.backend.dto.request.UpdateAppointmentStatusRequest
import com.aesthetic.backend.dto.response.AppointmentResponse
import com.aesthetic.backend.dto.response.AppointmentSummaryResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.exception.AppointmentConflictException
import com.aesthetic.backend.exception.ClientBlacklistedException
import com.aesthetic.backend.domain.notification.NotificationType
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.mapper.toSummaryResponse
import com.aesthetic.backend.repository.AppointmentRepository
import com.aesthetic.backend.repository.ServiceRepository
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.data.domain.Pageable
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@org.springframework.stereotype.Service
class AppointmentManagementService(
    private val appointmentRepository: AppointmentRepository,
    private val serviceRepository: ServiceRepository,
    private val userRepository: UserRepository,
    private val siteSettingsService: SiteSettingsService,
    private val availabilityService: AvailabilityService,
    private val notificationService: NotificationService,
    private val entityManager: EntityManager
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val NO_SHOW_BLACKLIST_THRESHOLD = 3
    }

    private val cancelledStatuses = listOf(AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)

    @CacheEvict(value = ["dashboard-stats"], keyGenerator = "tenantCacheKeyGenerator")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun createAppointment(request: CreateAppointmentRequest, clientId: String? = null): AppointmentResponse {
        val tenantId = TenantContext.getTenantId()
        val settings = siteSettingsService.getSettings()
        val tenantZone = ZoneId.of(settings.timezone)
        val today = LocalDate.now(tenantZone)

        // Resolve client by ID or email
        var resolvedClientId = clientId
        if (resolvedClientId != null) {
            val client = userRepository.findById(resolvedClientId).orElse(null)
            if (client != null && client.isBlacklisted) {
                throw ClientBlacklistedException("Kara listeye alınmış müşteriler randevu oluşturamaz")
            }
        } else if (!request.clientEmail.isNullOrBlank()) {
            val existingUser = userRepository.findByEmailAndTenantId(request.clientEmail, tenantId)
            if (existingUser != null) {
                if (existingUser.isBlacklisted) {
                    throw ClientBlacklistedException("Bu e-posta adresi kara listede")
                }
                resolvedClientId = existingUser.id
            }
        }

        // Past date check
        require(!request.date.isBefore(today)) { "Geçmiş tarihe randevu oluşturulamaz" }

        // Load services and calculate totals
        val services = serviceRepository.findAllById(request.serviceIds)
        if (services.size != request.serviceIds.size) {
            throw ResourceNotFoundException("Bazı hizmetler bulunamadı")
        }

        val orderedServices = request.serviceIds.mapNotNull { id -> services.find { it.id == id } }
        val totalDuration = orderedServices.sumOf { it.durationMinutes }
        val totalPrice = orderedServices.fold(java.math.BigDecimal.ZERO) { acc, svc -> acc.add(svc.price) }
        val lastBufferMinutes = orderedServices.last().bufferMinutes
        val endTime = request.startTime.plusMinutes(totalDuration.toLong())
        val endTimeWithBuffer = endTime.plusMinutes(lastBufferMinutes.toLong())

        // Resolve staff
        val staffId = request.staffId
            ?: availabilityService.findAvailableStaff(tenantId, request.date, request.startTime, endTimeWithBuffer)
            ?: throw AppointmentConflictException("Uygun personel bulunamadı")

        // Conflict detection (PESSIMISTIC_WRITE)
        val conflicts = appointmentRepository.findConflictingAppointments(
            tenantId, staffId, request.date, request.startTime, endTimeWithBuffer, cancelledStatuses
        )
        if (conflicts.isNotEmpty()) {
            throw AppointmentConflictException("Seçilen zaman diliminde çakışma var")
        }

        // Working hours check
        if (!availabilityService.isWithinWorkingHours(tenantId, staffId, request.date, request.startTime, endTimeWithBuffer)) {
            throw AppointmentConflictException("Seçilen saat çalışma saatleri dışında")
        }

        // Blocked slot check
        if (availabilityService.isTimeSlotBlocked(tenantId, staffId, request.date, request.startTime, endTimeWithBuffer)) {
            throw AppointmentConflictException("Seçilen zaman dilimi bloklanmış")
        }

        // Create appointment entity
        val appointment = Appointment(
            clientName = request.clientName,
            clientEmail = request.clientEmail ?: "",
            clientPhone = request.clientPhone,
            date = request.date,
            startTime = request.startTime,
            endTime = endTime,
            totalDurationMinutes = totalDuration,
            totalPrice = totalPrice,
            notes = request.notes,
            status = AppointmentStatus.PENDING
        )

        if (resolvedClientId != null) {
            appointment.client = entityManager.getReference(User::class.java, resolvedClientId)
        }
        appointment.staff = entityManager.getReference(User::class.java, staffId)
        appointment.primaryService = entityManager.getReference(Service::class.java, orderedServices.first().id)

        val saved = appointmentRepository.save(appointment)

        // Create pivot records (service snapshots)
        orderedServices.forEachIndexed { index, svc ->
            val apptService = AppointmentServiceEntity(
                appointment = saved,
                service = entityManager.getReference(Service::class.java, svc.id),
                price = svc.price,
                durationMinutes = svc.durationMinutes,
                sortOrder = index
            )
            saved.services.add(apptService)
        }
        appointmentRepository.save(saved)

        logger.debug("Appointment created: id={}, tenant={}, staff={}", saved.id, tenantId, staffId)

        val fullAppointment = appointmentRepository.findByIdWithDetails(saved.id!!)!!
        try {
            val ctx = notificationService.toContext(fullAppointment)
            notificationService.sendAppointmentConfirmation(ctx)
        } catch (e: Exception) {
            logger.error("Notification failed for new appointment: {}", saved.id, e)
        }

        return fullAppointment.toResponse()
    }

    @CacheEvict(value = ["dashboard-stats"], keyGenerator = "tenantCacheKeyGenerator")
    @Transactional
    fun updateStatus(id: String, request: UpdateAppointmentStatusRequest): AppointmentResponse {
        val appointment = findAppointmentOrThrow(id)

        if (!appointment.status.canTransitionTo(request.status)) {
            throw IllegalStateException(
                "Randevu durumu ${appointment.status} -> ${request.status} geçişi geçersiz"
            )
        }

        appointment.status = request.status

        if (request.status == AppointmentStatus.CANCELLED) {
            appointment.cancelledAt = Instant.now()
            appointment.cancellationReason = request.reason
        }

        if (request.status == AppointmentStatus.NO_SHOW) {
            handleNoShow(appointment)
        }

        appointmentRepository.save(appointment)

        val fullAppointment = appointmentRepository.findByIdWithDetails(id)!!
        try {
            val ctx = notificationService.toContext(fullAppointment)
            when (request.status) {
                AppointmentStatus.CONFIRMED -> notificationService.sendAppointmentConfirmation(ctx)
                AppointmentStatus.CANCELLED -> notificationService.sendAppointmentCancellation(ctx)
                AppointmentStatus.NO_SHOW -> notificationService.sendNotification(
                    ctx.copy(notificationType = NotificationType.NO_SHOW_WARNING)
                )
                else -> {}
            }
        } catch (e: Exception) {
            logger.error("Notification failed for status update: {}", id, e)
        }

        return fullAppointment.toResponse()
    }

    @CacheEvict(value = ["dashboard-stats"], keyGenerator = "tenantCacheKeyGenerator")
    @Transactional
    fun cancelByClient(id: String, clientUserId: String): AppointmentResponse {
        val appointment = findAppointmentOrThrow(id)

        if (appointment.client?.id != clientUserId) {
            throw AccessDeniedException("Bu randevuyu iptal etme yetkiniz yok")
        }

        if (!appointment.status.canTransitionTo(AppointmentStatus.CANCELLED)) {
            throw IllegalStateException("Bu randevu iptal edilemez")
        }

        // Cancellation policy check
        val settings = siteSettingsService.getSettings()
        val tenantZone = ZoneId.of(settings.timezone)
        val appointmentDateTime = LocalDateTime.of(appointment.date, appointment.startTime)
            .atZone(tenantZone).toInstant()
        val now = Instant.now()
        val hoursUntilAppointment = ChronoUnit.HOURS.between(now, appointmentDateTime)

        if (hoursUntilAppointment < settings.cancellationPolicyHours) {
            throw IllegalArgumentException(
                "Randevu ${settings.cancellationPolicyHours} saatten kısa sürede iptal edilemez"
            )
        }

        appointment.status = AppointmentStatus.CANCELLED
        appointment.cancelledAt = Instant.now()
        appointment.cancellationReason = "Müşteri tarafından iptal"

        appointmentRepository.save(appointment)

        val fullAppointment = appointmentRepository.findByIdWithDetails(id)!!
        try {
            val ctx = notificationService.toContext(fullAppointment)
            notificationService.sendAppointmentCancellation(ctx)
        } catch (e: Exception) {
            logger.error("Notification failed for client cancellation: {}", id, e)
        }

        return fullAppointment.toResponse()
    }

    @CacheEvict(value = ["dashboard-stats"], keyGenerator = "tenantCacheKeyGenerator")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun reschedule(id: String, request: RescheduleAppointmentRequest): AppointmentResponse {
        val tenantId = TenantContext.getTenantId()
        val appointment = findAppointmentOrThrow(id)

        require(appointment.status in listOf(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED)) {
            "Sadece bekleyen veya onaylanmış randevular yeniden planlanabilir"
        }

        // Cancellation policy check
        val settings = siteSettingsService.getSettings()
        val tenantZone = ZoneId.of(settings.timezone)
        val appointmentDateTime = LocalDateTime.of(appointment.date, appointment.startTime)
            .atZone(tenantZone).toInstant()
        val now = Instant.now()
        val hoursUntilAppointment = ChronoUnit.HOURS.between(now, appointmentDateTime)

        if (hoursUntilAppointment < settings.cancellationPolicyHours) {
            throw IllegalArgumentException(
                "Randevu ${settings.cancellationPolicyHours} saatten kısa sürede yeniden planlanamaz"
            )
        }

        val newEndTime = request.startTime.plusMinutes(appointment.totalDurationMinutes.toLong())
        val lastService = appointment.services.maxByOrNull { it.sortOrder }
        val bufferMinutes = lastService?.service?.bufferMinutes ?: 0
        val newEndTimeWithBuffer = newEndTime.plusMinutes(bufferMinutes.toLong())

        val staffId = request.staffId ?: appointment.staff?.id
            ?: throw AppointmentConflictException("Personel bilgisi eksik")

        // Conflict check (exclude self)
        val conflicts = appointmentRepository.findConflictingAppointments(
            tenantId, staffId, request.date, request.startTime, newEndTimeWithBuffer, cancelledStatuses
        ).filter { it.id != appointment.id }

        if (conflicts.isNotEmpty()) {
            throw AppointmentConflictException("Yeni zaman diliminde çakışma var")
        }

        if (!availabilityService.isWithinWorkingHours(tenantId, staffId, request.date, request.startTime, newEndTimeWithBuffer)) {
            throw AppointmentConflictException("Seçilen saat çalışma saatleri dışında")
        }

        if (availabilityService.isTimeSlotBlocked(tenantId, staffId, request.date, request.startTime, newEndTimeWithBuffer)) {
            throw AppointmentConflictException("Seçilen zaman dilimi bloklanmış")
        }

        appointment.date = request.date
        appointment.startTime = request.startTime
        appointment.endTime = newEndTime

        if (request.staffId != null && request.staffId != appointment.staff?.id) {
            appointment.staff = entityManager.getReference(User::class.java, request.staffId)
        }

        appointmentRepository.save(appointment)

        val fullAppointment = appointmentRepository.findByIdWithDetails(id)!!
        try {
            val ctx = notificationService.toContext(fullAppointment)
            notificationService.sendAppointmentRescheduled(ctx)
        } catch (e: Exception) {
            logger.error("Notification failed for reschedule: {}", id, e)
        }

        return fullAppointment.toResponse()
    }

    @Transactional(readOnly = true)
    fun getById(id: String): AppointmentResponse {
        return appointmentRepository.findByIdWithDetails(id)?.toResponse()
            ?: throw ResourceNotFoundException("Randevu bulunamadı: $id")
    }

    @Transactional(readOnly = true)
    fun listForAdmin(
        date: LocalDate?,
        status: AppointmentStatus?,
        staffId: String?,
        pageable: Pageable
    ): PagedResponse<AppointmentSummaryResponse> {
        val tenantId = TenantContext.getTenantId()
        return appointmentRepository.findFiltered(tenantId, date, status, staffId, pageable)
            .toPagedResponse { it.toSummaryResponse() }
    }

    @Transactional(readOnly = true)
    fun listForClient(
        clientId: String,
        status: AppointmentStatus?,
        pageable: Pageable
    ): PagedResponse<AppointmentSummaryResponse> {
        val tenantId = TenantContext.getTenantId()
        return appointmentRepository.findByClientId(tenantId, clientId, status, pageable)
            .toPagedResponse { it.toSummaryResponse() }
    }

    @Transactional(readOnly = true)
    fun getStaffCalendar(staffId: String, date: LocalDate): List<AppointmentSummaryResponse> {
        val tenantId = TenantContext.getTenantId()
        return appointmentRepository.findByStaffAndDate(
            tenantId, staffId, date, listOf(AppointmentStatus.CANCELLED)
        ).map { it.toSummaryResponse() }
    }

    private fun findAppointmentOrThrow(id: String): Appointment {
        return appointmentRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Randevu bulunamadı: $id") }
    }

    private fun handleNoShow(appointment: Appointment) {
        val client = appointment.client ?: return
        client.noShowCount++
        if (client.noShowCount >= NO_SHOW_BLACKLIST_THRESHOLD) {
            client.isBlacklisted = true
            client.blacklistedAt = Instant.now()
            client.blacklistReason = "Otomatik: $NO_SHOW_BLACKLIST_THRESHOLD kez randevuya gelmedi"
            logger.warn(
                "[tenant={}] Client blacklisted: userId={}, noShowCount={}",
                TenantContext.getTenantIdOrNull(), client.id, client.noShowCount
            )
            try {
                val ctx = notificationService.toContext(client, NotificationType.BLACKLIST)
                notificationService.sendNotification(ctx)
            } catch (e: Exception) {
                logger.error("Blacklist notification failed for userId: {}", client.id, e)
            }
        }
        userRepository.save(client)
    }
}
