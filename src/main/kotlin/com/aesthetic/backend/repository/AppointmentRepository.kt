package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.appointment.Appointment
import com.aesthetic.backend.domain.appointment.AppointmentStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

interface AppointmentRepository : JpaRepository<Appointment, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.tenantId = :tenantId
        AND a.staff.id = :staffId
        AND a.date = :date
        AND a.status NOT IN :excludeStatuses
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    fun findConflictingAppointments(
        @Param("tenantId") tenantId: String,
        @Param("staffId") staffId: String,
        @Param("date") date: LocalDate,
        @Param("startTime") startTime: LocalTime,
        @Param("endTime") endTime: LocalTime,
        @Param("excludeStatuses") excludeStatuses: List<AppointmentStatus>
    ): List<Appointment>

    @Query("""
        SELECT a FROM Appointment a
        WHERE a.tenantId = :tenantId
        AND a.staff.id = :staffId
        AND a.date = :date
        AND a.status NOT IN :excludeStatuses
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    fun findConflicts(
        @Param("tenantId") tenantId: String,
        @Param("staffId") staffId: String,
        @Param("date") date: LocalDate,
        @Param("startTime") startTime: LocalTime,
        @Param("endTime") endTime: LocalTime,
        @Param("excludeStatuses") excludeStatuses: List<AppointmentStatus>
    ): List<Appointment>

    @Query("""
        SELECT a FROM Appointment a
        WHERE a.tenantId = :tenantId
        AND (:date IS NULL OR a.date = :date)
        AND (:status IS NULL OR a.status = :status)
        AND (:staffId IS NULL OR a.staff.id = :staffId)
        ORDER BY a.date DESC, a.startTime ASC
    """)
    fun findFiltered(
        @Param("tenantId") tenantId: String,
        @Param("date") date: LocalDate?,
        @Param("status") status: AppointmentStatus?,
        @Param("staffId") staffId: String?,
        pageable: Pageable
    ): Page<Appointment>

    @Query("""
        SELECT a FROM Appointment a
        WHERE a.tenantId = :tenantId
        AND a.client.id = :clientId
        AND (:status IS NULL OR a.status = :status)
        ORDER BY a.date DESC, a.startTime DESC
    """)
    fun findByClientId(
        @Param("tenantId") tenantId: String,
        @Param("clientId") clientId: String,
        @Param("status") status: AppointmentStatus?,
        pageable: Pageable
    ): Page<Appointment>

    @Query("""
        SELECT a FROM Appointment a
        WHERE a.tenantId = :tenantId
        AND a.staff.id = :staffId
        AND a.date = :date
        AND a.status NOT IN :excludeStatuses
        ORDER BY a.startTime ASC
    """)
    fun findByStaffAndDate(
        @Param("tenantId") tenantId: String,
        @Param("staffId") staffId: String,
        @Param("date") date: LocalDate,
        @Param("excludeStatuses") excludeStatuses: List<AppointmentStatus>
    ): List<Appointment>

    @Query("""
        SELECT DISTINCT a FROM Appointment a
        LEFT JOIN FETCH a.services s
        LEFT JOIN FETCH s.service
        LEFT JOIN FETCH a.staff
        LEFT JOIN FETCH a.client
        WHERE a.id = :id
    """)
    fun findByIdWithDetails(@Param("id") id: String): Appointment?

    @Query("""
        SELECT
            COUNT(a),
            COALESCE(SUM(CASE WHEN a.status = com.aesthetic.backend.domain.appointment.AppointmentStatus.COMPLETED THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN a.status = com.aesthetic.backend.domain.appointment.AppointmentStatus.PENDING THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN a.status = com.aesthetic.backend.domain.appointment.AppointmentStatus.CONFIRMED THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN a.status = com.aesthetic.backend.domain.appointment.AppointmentStatus.CANCELLED THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN a.status = com.aesthetic.backend.domain.appointment.AppointmentStatus.NO_SHOW THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN a.status = com.aesthetic.backend.domain.appointment.AppointmentStatus.COMPLETED THEN a.totalPrice ELSE 0 END), 0)
        FROM Appointment a
        WHERE a.tenantId = :tenantId AND a.date = :date
    """)
    fun getDailyStats(
        @Param("tenantId") tenantId: String,
        @Param("date") date: LocalDate
    ): Array<Any>

    @Query("""
        SELECT a FROM Appointment a
        WHERE a.tenantId = :tenantId
        AND a.status = com.aesthetic.backend.domain.appointment.AppointmentStatus.CONFIRMED
        AND (a.date < :cutoffDate OR (a.date = :cutoffDate AND a.endTime <= :cutoffTime))
    """)
    fun findConfirmedPastAppointments(
        @Param("tenantId") tenantId: String,
        @Param("cutoffDate") cutoffDate: LocalDate,
        @Param("cutoffTime") cutoffTime: LocalTime
    ): List<Appointment>

    fun countByTenantIdAndCreatedAtAfter(tenantId: String, after: Instant): Long

    @Query("""
        SELECT a FROM Appointment a
        LEFT JOIN FETCH a.staff
        LEFT JOIN FETCH a.client
        WHERE a.tenantId = :tenantId
        AND a.status = com.aesthetic.backend.domain.appointment.AppointmentStatus.CONFIRMED
        AND a.date = :date
        AND a.startTime BETWEEN :fromTime AND :toTime
        AND a.reminder24hSent = false
    """)
    fun findUnsentReminders24h(
        @Param("tenantId") tenantId: String,
        @Param("date") date: LocalDate,
        @Param("fromTime") fromTime: LocalTime,
        @Param("toTime") toTime: LocalTime
    ): List<Appointment>

    @Query("""
        SELECT a FROM Appointment a
        LEFT JOIN FETCH a.staff
        LEFT JOIN FETCH a.client
        WHERE a.tenantId = :tenantId
        AND a.status = com.aesthetic.backend.domain.appointment.AppointmentStatus.CONFIRMED
        AND a.date = :date
        AND a.startTime BETWEEN :fromTime AND :toTime
        AND a.reminder1hSent = false
    """)
    fun findUnsentReminders1h(
        @Param("tenantId") tenantId: String,
        @Param("date") date: LocalDate,
        @Param("fromTime") fromTime: LocalTime,
        @Param("toTime") toTime: LocalTime
    ): List<Appointment>

    @Modifying
    @Query("UPDATE Appointment a SET a.reminder24hSent = true WHERE a.id IN :ids")
    fun markReminder24hSent(@Param("ids") ids: List<String>)

    @Modifying
    @Query("UPDATE Appointment a SET a.reminder1hSent = true WHERE a.id IN :ids")
    fun markReminder1hSent(@Param("ids") ids: List<String>)

    @Query("""
        SELECT COUNT(a)
        FROM Appointment a
        WHERE a.tenantId = :tenantId AND a.client.id = :clientId
    """)
    fun countByTenantIdAndClientId(
        @Param("tenantId") tenantId: String,
        @Param("clientId") clientId: String
    ): Long

    @Query("""
        SELECT MAX(a.date)
        FROM Appointment a
        WHERE a.tenantId = :tenantId AND a.client.id = :clientId
    """)
    fun findLastAppointmentDateByClientId(
        @Param("tenantId") tenantId: String,
        @Param("clientId") clientId: String
    ): LocalDate?

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN a.status = com.aesthetic.backend.domain.appointment.AppointmentStatus.COMPLETED THEN a.totalPrice ELSE 0 END), 0)
        FROM Appointment a
        WHERE a.tenantId = :tenantId AND a.client.id = :clientId
    """)
    fun sumTotalSpentByClientId(
        @Param("tenantId") tenantId: String,
        @Param("clientId") clientId: String
    ): java.math.BigDecimal

    @Modifying
    @Query("UPDATE Appointment a SET a.clientName = 'Anonim', a.clientEmail = '', a.clientPhone = '' WHERE a.clientEmail = :email")
    fun anonymizeByClientEmail(@Param("email") email: String)

    @Modifying
    @Query("UPDATE Appointment a SET a.clientName = 'Anonim', a.clientEmail = '', a.clientPhone = '' WHERE a.client.id = :clientId")
    fun anonymizeByClientId(@Param("clientId") clientId: String)
}
