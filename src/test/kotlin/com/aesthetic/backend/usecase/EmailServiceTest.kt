package com.aesthetic.backend.usecase

import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender

@ExtendWith(MockKExtension::class)
class EmailServiceTest {

    @MockK
    private lateinit var mailSender: JavaMailSender

    private lateinit var emailService: EmailService

    @BeforeEach
    fun setUp() {
        emailService = EmailService(mailSender)
    }

    @Test
    fun `sendEmail should send email successfully`() {
        val mimeMessage = mockk<MimeMessage>(relaxed = true)
        every { mailSender.createMimeMessage() } returns mimeMessage
        every { mailSender.send(mimeMessage) } just Runs

        assertDoesNotThrow {
            emailService.sendEmail("client@example.com", "Randevunuz Onaylandı", "<h1>Onay</h1>")
        }

        verify { mailSender.createMimeMessage() }
        verify { mailSender.send(mimeMessage) }
    }

    @Test
    fun `sendEmail should throw exception when send fails`() {
        val mimeMessage = mockk<MimeMessage>(relaxed = true)
        every { mailSender.createMimeMessage() } returns mimeMessage
        every { mailSender.send(mimeMessage) } throws MailSendException("SMTP connection failed")

        val exception = assertThrows<MailSendException> {
            emailService.sendEmail("client@example.com", "Test Subject", "<p>Test Body</p>")
        }

        assertEquals("SMTP connection failed", exception.message)
    }

    @Test
    fun `sendEmail should throw exception when createMimeMessage fails`() {
        every { mailSender.createMimeMessage() } throws RuntimeException("Mail configuration error")

        assertThrows<RuntimeException> {
            emailService.sendEmail("client@example.com", "Subject", "Body")
        }

        verify(exactly = 0) { mailSender.send(any<MimeMessage>()) }
    }

    @Test
    fun `sendEmail should re-throw exception and not swallow it`() {
        val mimeMessage = mockk<MimeMessage>(relaxed = true)
        every { mailSender.createMimeMessage() } returns mimeMessage
        every { mailSender.send(mimeMessage) } throws RuntimeException("Unexpected error")

        // Per CLAUDE.md rule: exception must NOT be swallowed, it must be thrown
        // so that @Retryable can trigger retries
        assertThrows<RuntimeException> {
            emailService.sendEmail("client@example.com", "Subject", "Body")
        }
    }
}
