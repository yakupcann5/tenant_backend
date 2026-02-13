package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.appointment.Appointment
import com.aesthetic.backend.domain.notification.DeliveryStatus
import com.aesthetic.backend.domain.notification.Notification
import com.aesthetic.backend.domain.notification.NotificationType
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.notification.NotificationContext
import com.aesthetic.backend.dto.response.NotificationResponse
import com.aesthetic.backend.dto.response.NotificationTemplateResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.dto.response.toPagedResponse
import com.aesthetic.backend.dto.request.UpdateNotificationTemplateRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.mapper.toResponse
import com.aesthetic.backend.repository.NotificationRepository
import com.aesthetic.backend.repository.NotificationTemplateRepository
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.util.HtmlUtils
import java.time.Instant

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val notificationTemplateRepository: NotificationTemplateRepository,
    private val emailService: EmailService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun toContext(appointment: Appointment): NotificationContext {
        return NotificationContext(
            tenantId = appointment.tenantId,
            recipientEmail = appointment.clientEmail,
            recipientPhone = appointment.clientPhone,
            recipientName = appointment.clientName,
            recipientId = appointment.client?.id,
            variables = mapOf(
                "clientName" to appointment.clientName,
                "date" to appointment.date.toString(),
                "startTime" to appointment.startTime.toString(),
                "endTime" to appointment.endTime.toString(),
                "staffName" to (appointment.staff?.let { "${it.firstName} ${it.lastName}".trim() } ?: "")
            ),
            notificationType = NotificationType.APPOINTMENT_CONFIRMATION
        )
    }

    fun toContext(user: User, type: NotificationType): NotificationContext {
        return NotificationContext(
            tenantId = user.tenantId,
            recipientEmail = user.email,
            recipientPhone = user.phone,
            recipientName = "${user.firstName} ${user.lastName}".trim(),
            recipientId = user.id,
            variables = mapOf(
                "clientName" to "${user.firstName} ${user.lastName}".trim(),
                "email" to user.email
            ),
            notificationType = type
        )
    }

    @Async
    @Retryable(maxAttempts = 3, backoff = Backoff(delay = 2000))
    fun sendNotification(ctx: NotificationContext) {
        try {
            TenantContext.setTenantId(ctx.tenantId)

            val template = notificationTemplateRepository.findByTenantIdAndTypeAndIsActiveTrue(
                ctx.tenantId, ctx.notificationType
            )

            val subject = template?.let { renderTemplate(it.subject, ctx.variables) }
                ?: getDefaultSubject(ctx.notificationType)
            val body = template?.let { renderTemplate(it.body, ctx.variables) }
                ?: getDefaultBody(ctx.notificationType, ctx.variables)

            val notification = Notification(
                type = ctx.notificationType,
                recipientId = ctx.recipientId,
                recipientEmail = ctx.recipientEmail,
                recipientPhone = ctx.recipientPhone,
                subject = subject,
                body = body
            )
            notification.tenantId = ctx.tenantId

            if (ctx.recipientEmail.isNotBlank()) {
                emailService.sendEmail(ctx.recipientEmail, subject, body)
                notification.deliveryStatus = DeliveryStatus.SENT
                notification.sentAt = Instant.now()
            }

            notificationRepository.save(notification)
            logger.debug("Notification sent: type={}, to={}", ctx.notificationType, ctx.recipientEmail)
        } catch (e: Exception) {
            logger.error("Notification failed: type={}, to={}, error={}",
                ctx.notificationType, ctx.recipientEmail, e.message)

            val failedNotification = Notification(
                type = ctx.notificationType,
                recipientId = ctx.recipientId,
                recipientEmail = ctx.recipientEmail,
                recipientPhone = ctx.recipientPhone,
                subject = "",
                body = "",
                deliveryStatus = DeliveryStatus.FAILED,
                errorMessage = e.message
            )
            failedNotification.tenantId = ctx.tenantId
            notificationRepository.save(failedNotification)

            throw e
        } finally {
            TenantContext.clear()
        }
    }

    @Async
    @Retryable(maxAttempts = 3, backoff = Backoff(delay = 2000))
    fun sendAppointmentConfirmation(ctx: NotificationContext) {
        sendNotification(ctx.copy(notificationType = NotificationType.APPOINTMENT_CONFIRMATION))
    }

    @Async
    @Retryable(maxAttempts = 3, backoff = Backoff(delay = 2000))
    fun sendAppointmentReminder(ctx: NotificationContext) {
        sendNotification(ctx)
    }

    @Async
    @Retryable(maxAttempts = 3, backoff = Backoff(delay = 2000))
    fun sendAppointmentCancellation(ctx: NotificationContext) {
        sendNotification(ctx.copy(notificationType = NotificationType.CANCELLED))
    }

    @Async
    @Retryable(maxAttempts = 3, backoff = Backoff(delay = 2000))
    fun sendAppointmentRescheduled(ctx: NotificationContext) {
        sendNotification(ctx.copy(notificationType = NotificationType.RESCHEDULED))
    }

    @Transactional(readOnly = true)
    fun listLogs(pageable: Pageable): PagedResponse<NotificationResponse> {
        val tenantId = TenantContext.getTenantId()
        return notificationRepository.findAllByTenantId(tenantId, pageable)
            .toPagedResponse { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun listTemplates(): List<NotificationTemplateResponse> {
        val tenantId = TenantContext.getTenantId()
        return notificationTemplateRepository.findAllByTenantId(tenantId)
            .map { it.toResponse() }
    }

    @Transactional
    fun updateTemplate(id: String, request: UpdateNotificationTemplateRequest): NotificationTemplateResponse {
        val template = notificationTemplateRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Şablon bulunamadı: $id") }

        request.subject?.let { template.subject = it }
        request.body?.let { template.body = it }
        request.isActive?.let { template.isActive = it }

        return notificationTemplateRepository.save(template).toResponse()
    }

    fun renderTemplate(template: String, variables: Map<String, String>): String {
        var result = template
        variables.forEach { (key, value) ->
            result = result.replace("{{$key}}", HtmlUtils.htmlEscape(value))
        }
        return result
    }

    private fun getDefaultSubject(type: NotificationType): String = when (type) {
        NotificationType.APPOINTMENT_CONFIRMATION -> "Randevunuz Onaylandı"
        NotificationType.REMINDER_24H -> "Randevu Hatırlatma - Yarın"
        NotificationType.REMINDER_1H -> "Randevu Hatırlatma - 1 Saat"
        NotificationType.CANCELLED -> "Randevunuz İptal Edildi"
        NotificationType.RESCHEDULED -> "Randevunuz Yeniden Planlandı"
        NotificationType.NO_SHOW_WARNING -> "Randevuya Gelmeme Uyarısı"
        NotificationType.BLACKLIST -> "Hesap Kısıtlaması"
        NotificationType.WELCOME -> "Hoş Geldiniz"
        NotificationType.PASSWORD_RESET -> "Şifre Sıfırlama"
        NotificationType.TRIAL_EXPIRING -> "Deneme Süreniz Doluyor"
        NotificationType.SUBSCRIPTION_RENEWED -> "Aboneliğiniz Yenilendi"
    }

    private fun getDefaultBody(type: NotificationType, variables: Map<String, String>): String {
        val name = variables["clientName"] ?: ""
        return when (type) {
            NotificationType.APPOINTMENT_CONFIRMATION ->
                "Sayın $name, ${variables["date"]} tarihli ${variables["startTime"]} randevunuz onaylanmıştır."
            NotificationType.REMINDER_24H ->
                "Sayın $name, yarın ${variables["startTime"]} saatinde randevunuz bulunmaktadır."
            NotificationType.REMINDER_1H ->
                "Sayın $name, 1 saat içinde randevunuz bulunmaktadır."
            NotificationType.CANCELLED ->
                "Sayın $name, ${variables["date"]} tarihli randevunuz iptal edilmiştir."
            NotificationType.RESCHEDULED ->
                "Sayın $name, randevunuz ${variables["date"]} tarihine yeniden planlanmıştır."
            else -> "Bildirim: $type"
        }
    }
}
