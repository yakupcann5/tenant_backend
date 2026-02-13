package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.schedule.BlockedTimeSlot
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface BlockedTimeSlotRepository : JpaRepository<BlockedTimeSlot, String> {

    @Query("""
        SELECT b FROM BlockedTimeSlot b
        WHERE b.tenantId = :tenantId
        AND (b.staff.id = :staffId OR b.staff IS NULL)
        AND b.date = :date
    """)
    fun findByStaffAndDate(
        @Param("tenantId") tenantId: String,
        @Param("staffId") staffId: String,
        @Param("date") date: LocalDate
    ): List<BlockedTimeSlot>

    @Query("""
        SELECT b FROM BlockedTimeSlot b
        WHERE b.tenantId = :tenantId
        AND b.staff IS NULL
        AND b.date = :date
    """)
    fun findFacilityBlocksByDate(
        @Param("tenantId") tenantId: String,
        @Param("date") date: LocalDate
    ): List<BlockedTimeSlot>

    @Query("""
        SELECT b FROM BlockedTimeSlot b
        WHERE b.tenantId = :tenantId
        AND (:staffId IS NULL OR b.staff.id = :staffId)
        ORDER BY b.date ASC, b.startTime ASC
    """)
    fun findByTenantAndOptionalStaff(
        @Param("tenantId") tenantId: String,
        @Param("staffId") staffId: String?,
        pageable: Pageable
    ): Page<BlockedTimeSlot>
}
