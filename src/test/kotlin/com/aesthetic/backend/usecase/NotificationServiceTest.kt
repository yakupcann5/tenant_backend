package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.appointment.Appointment
import com.aesthetic.backend.domain.notification.*
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.notification.NotificationContext
import com.aesthetic.backend.dto.request.UpdateNotificationTemplateRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.NotificationRepository
import com.aesthetic.backend.repository.NotificationTemplateRepository
import com.aesthetic.backend.tenant.TenantContext
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.LocalDate
import java.time.LocalTime
import java.util.*

@ExtendWith(MockKExtension::class)
class NotificationServiceTest {

    @MockK
    private lateinit var notificationRepository: NotificationRepository

    @MockK
    private lateinit var notificationTemplateRepository: NotificationTemplateRepository

    @MockK
    private lateinit var emailService: EmailService

    private lateinit var notificationService: NotificationService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        notificationService = NotificationService(
            notificationRepository,
            notificationTemplateRepository,
            emailService
        )
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `sendNotification should send email and save notification with SENT status`() {
        val ctx = createNotificationContext()
        val template = createNotificationTemplate()

        every { notificationTemplateRepository.findByTenantIdAndTypeAndIsActiveTrue(tenantId, NotificationType.APPOINTMENT_CONFIRMATION) } returns template
        every { emailService.sendEmail(any(), any(), any()) } just Runs
        every { notificationRepository.save(any()) } answers {
            (firstArg() as Notification).apply {
                val idField = Notification::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "notif-1")
            }
        }

        notificationService.sendNotification(ctx)

        verify { emailService.sendEmail("client@example.com", any(), any()) }
        verify {
            notificationRepository.save(match {
                it.deliveryStatus == DeliveryStatus.SENT &&
                it.recipientEmail == "client@example.com" &&
                it.tenantId == tenantId &&
                it.sentAt != null
            })
        }
    }

    @Test
    fun `sendNotification should use default subject when no template exists`() {
        val ctx = createNotificationContext()

        every { notificationTemplateRepository.findByTenantIdAndTypeAndIsActiveTrue(tenantId, NotificationType.APPOINTMENT_CONFIRMATION) } returns null
        every { emailService.sendEmail(any(), any(), any()) } just Runs
        every { notificationRepository.save(any()) } answers { firstArg() }

        notificationService.sendNotification(ctx)

        verify {
            emailService.sendEmail("client@example.com", "Randevunuz Onaylandı", any())
        }
    }

    @Test
    fun `sendNotification should save FAILED notification and re-throw on email failure`() {
        val ctx = createNotificationContext()

        every { notificationTemplateRepository.findByTenantIdAndTypeAndIsActiveTrue(tenantId, NotificationType.APPOINTMENT_CONFIRMATION) } returns null
        every { emailService.sendEmail(any(), any(), any()) } throws RuntimeException("SMTP error")
        every { notificationRepository.save(any()) } answers { firstArg() }

        assertThrows<RuntimeException> {
            notificationService.sendNotification(ctx)
        }

        verify {
            notificationRepository.save(match {
                it.deliveryStatus == DeliveryStatus.FAILED &&
                it.errorMessage == "SMTP error"
            })
        }
    }

    @Test
    fun `sendNotification should not send email when recipientEmail is blank`() {
        val ctx = createNotificationContext().copy(recipientEmail = "")

        every { notificationTemplateRepository.findByTenantIdAndTypeAndIsActiveTrue(tenantId, NotificationType.APPOINTMENT_CONFIRMATION) } returns null
        every { notificationRepository.save(any()) } answers { firstArg() }

        notificationService.sendNotification(ctx)

        verify(exactly = 0) { emailService.sendEmail(any(), any(), any()) }
        verify {
            notificationRepository.save(match {
                it.deliveryStatus == DeliveryStatus.PENDING
            })
        }
    }

    @Test
    fun `toContext should convert Appointment to NotificationContext`() {
        val staff = User(
            id = "staff-1",
            firstName = "Mehmet",
            lastName = "Demir",
            email = "mehmet@example.com"
        ).apply { tenantId = this@NotificationServiceTest.tenantId }

        val appointment = Appointment(
            id = "appt-1",
            clientName = "Ali Yilmaz",
            clientEmail = "ali@example.com",
            clientPhone = "05551234567",
            date = LocalDate.of(2025, 6, 15),
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 0)
        ).apply {
            tenantId = this@NotificationServiceTest.tenantId
            this.staff = staff
        }

        val ctx = notificationService.toContext(appointment)

        assertEquals(tenantId, ctx.tenantId)
        assertEquals("ali@example.com", ctx.recipientEmail)
        assertEquals("05551234567", ctx.recipientPhone)
        assertEquals("Ali Yilmaz", ctx.recipientName)
        assertEquals(NotificationType.APPOINTMENT_CONFIRMATION, ctx.notificationType)
        assertEquals("Ali Yilmaz", ctx.variables["clientName"])
        assertEquals("2025-06-15", ctx.variables["date"])
        assertEquals("10:00", ctx.variables["startTime"])
        assertEquals("11:00", ctx.variables["endTime"])
        assertEquals("Mehmet Demir", ctx.variables["staffName"])
    }

    @Test
    fun `toContext should handle appointment without staff`() {
        val appointment = Appointment(
            id = "appt-1",
            clientName = "Ali Yilmaz",
            clientEmail = "ali@example.com",
            clientPhone = "05551234567",
            date = LocalDate.of(2025, 6, 15),
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 0)
        ).apply {
            tenantId = this@NotificationServiceTest.tenantId
        }

        val ctx = notificationService.toContext(appointment)

        assertEquals("", ctx.variables["staffName"])
    }

    @Test
    fun `renderTemplate should replace variables with HTML-escaped values`() {
        val template = "Merhaba {{clientName}}, {{date}} tarihli randevunuz onaylandı."
        val variables = mapOf(
            "clientName" to "Ali Yilmaz",
            "date" to "2025-06-15"
        )

        val result = notificationService.renderTemplate(template, variables)

        assertEquals("Merhaba Ali Yilmaz, 2025-06-15 tarihli randevunuz onaylandı.", result)
    }

    @Test
    fun `renderTemplate should HTML-escape special characters`() {
        val template = "Merhaba {{clientName}}"
        val variables = mapOf("clientName" to "<script>alert('xss')</script>")

        val result = notificationService.renderTemplate(template, variables)

        assertFalse(result.contains("<script>"))
        assertTrue(result.contains("&lt;script&gt;"))
    }

    @Test
    fun `renderTemplate should leave template unchanged when variable not in map`() {
        val template = "Merhaba {{clientName}}, {{unknownVar}}"
        val variables = mapOf("clientName" to "Ali")

        val result = notificationService.renderTemplate(template, variables)

        assertEquals("Merhaba Ali, {{unknownVar}}", result)
    }

    @Test
    fun `listLogs should return paged notification logs`() {
        val pageable = PageRequest.of(0, 20)
        val notification = Notification(
            id = "notif-1",
            type = NotificationType.APPOINTMENT_CONFIRMATION,
            recipientEmail = "ali@example.com",
            subject = "Onay",
            body = "Randevunuz onaylandı",
            deliveryStatus = DeliveryStatus.SENT
        ).apply { tenantId = this@NotificationServiceTest.tenantId }

        val page = PageImpl(listOf(notification), pageable, 1)
        every { notificationRepository.findAllByTenantId(tenantId, pageable) } returns page

        val result = notificationService.listLogs(pageable)

        assertEquals(1, result.data.size)
        assertEquals("notif-1", result.data[0].id)
        assertEquals(DeliveryStatus.SENT, result.data[0].deliveryStatus)
    }

    @Test
    fun `listTemplates should return all templates for tenant`() {
        val template = createNotificationTemplate()
        every { notificationTemplateRepository.findAllByTenantId(tenantId) } returns listOf(template)

        val result = notificationService.listTemplates()

        assertEquals(1, result.size)
        assertEquals(NotificationType.APPOINTMENT_CONFIRMATION, result[0].type)
    }

    @Test
    fun `updateTemplate should update provided fields`() {
        val template = createNotificationTemplate()
        every { notificationTemplateRepository.findById("tmpl-1") } returns Optional.of(template)
        every { notificationTemplateRepository.save(any()) } answers { firstArg() }

        val request = UpdateNotificationTemplateRequest(
            subject = "Yeni Konu",
            body = "Yeni icerik",
            isActive = false
        )
        val result = notificationService.updateTemplate("tmpl-1", request)

        assertEquals("Yeni Konu", result.subject)
        assertEquals("Yeni icerik", result.body)
        assertFalse(result.isActive)
    }

    @Test
    fun `updateTemplate should throw when template not found`() {
        every { notificationTemplateRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            notificationService.updateTemplate("nonexistent", UpdateNotificationTemplateRequest())
        }
    }

    @Test
    fun `updateTemplate should only update non-null fields`() {
        val template = createNotificationTemplate()
        every { notificationTemplateRepository.findById("tmpl-1") } returns Optional.of(template)
        every { notificationTemplateRepository.save(any()) } answers { firstArg() }

        val request = UpdateNotificationTemplateRequest(subject = "Yeni Konu")
        val result = notificationService.updateTemplate("tmpl-1", request)

        assertEquals("Yeni Konu", result.subject)
        assertEquals("Sayın {{clientName}}, randevunuz onaylandı.", result.body)
        assertTrue(result.isActive)
    }

    private fun createNotificationContext() = NotificationContext(
        tenantId = tenantId,
        recipientEmail = "client@example.com",
        recipientPhone = "05551234567",
        recipientName = "Ali Yilmaz",
        recipientId = "user-1",
        variables = mapOf(
            "clientName" to "Ali Yilmaz",
            "date" to "2025-06-15",
            "startTime" to "10:00",
            "endTime" to "11:00",
            "staffName" to "Mehmet Demir"
        ),
        notificationType = NotificationType.APPOINTMENT_CONFIRMATION
    )

    private fun createNotificationTemplate() = NotificationTemplate(
        id = "tmpl-1",
        type = NotificationType.APPOINTMENT_CONFIRMATION,
        subject = "Randevu Onayı - {{clientName}}",
        body = "Sayın {{clientName}}, randevunuz onaylandı.",
        isActive = true
    ).apply { tenantId = this@NotificationServiceTest.tenantId }
}
