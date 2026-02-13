package com.aesthetic.backend.usecase

import org.slf4j.LoggerFactory
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service

@Service
class EmailService(
    private val mailSender: JavaMailSender
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun sendEmail(to: String, subject: String, body: String) {
        try {
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, "UTF-8")
            helper.setTo(to)
            helper.setSubject(subject)
            helper.setText(body, true)
            mailSender.send(message)
            logger.debug("Email sent to={}, subject={}", to, subject)
        } catch (e: Exception) {
            logger.error("Failed to send email to={}: {}", to, e.message)
            throw e
        }
    }
}
