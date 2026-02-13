package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.notification.NotificationTemplate
import com.aesthetic.backend.domain.notification.NotificationType
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationTemplateRepository : JpaRepository<NotificationTemplate, String> {
    fun findByTenantIdAndTypeAndIsActiveTrue(tenantId: String, type: NotificationType): NotificationTemplate?
    fun findAllByTenantId(tenantId: String): List<NotificationTemplate>
}
