package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.subscription.Subscription
import com.aesthetic.backend.domain.subscription.SubscriptionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface SubscriptionRepository : JpaRepository<Subscription, String> {

    @Query("SELECT s FROM Subscription s WHERE s.tenantId = :tenantId")
    fun findByTenantId(@Param("tenantId") tenantId: String): Subscription?

    fun findAllByStatus(status: SubscriptionStatus): List<Subscription>

    @Query("SELECT s FROM Subscription s WHERE s.status = :status AND s.trialEndDate <= :date")
    fun findAllByStatusAndTrialEndDateBefore(
        @Param("status") status: SubscriptionStatus,
        @Param("date") date: LocalDate
    ): List<Subscription>

    @Query("SELECT s FROM Subscription s WHERE s.status = :status AND s.retryCount < :maxRetry")
    fun findAllByStatusAndRetryCountLessThan(
        @Param("status") status: SubscriptionStatus,
        @Param("maxRetry") maxRetry: Int
    ): List<Subscription>
}
