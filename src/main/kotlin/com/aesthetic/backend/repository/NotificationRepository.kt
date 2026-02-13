package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.notification.Notification
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface NotificationRepository : JpaRepository<Notification, String> {
    fun findAllByTenantId(tenantId: String, pageable: Pageable): Page<Notification>

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.createdAt < :before")
    fun deleteByCreatedAtBefore(@Param("before") before: Instant)
}
