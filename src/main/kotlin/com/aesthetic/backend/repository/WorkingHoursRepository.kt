package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.schedule.WorkingHours
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.DayOfWeek

interface WorkingHoursRepository : JpaRepository<WorkingHours, String> {
    @Query("SELECT w FROM WorkingHours w WHERE w.tenantId = :tenantId AND w.staff IS NULL ORDER BY w.dayOfWeek ASC")
    fun findFacilityHours(tenantId: String): List<WorkingHours>

    @Query("SELECT w FROM WorkingHours w WHERE w.tenantId = :tenantId AND w.staff.id = :staffId ORDER BY w.dayOfWeek ASC")
    fun findByStaffId(tenantId: String, staffId: String): List<WorkingHours>

    @Query("SELECT w FROM WorkingHours w WHERE w.tenantId = :tenantId AND w.staff IS NULL AND w.dayOfWeek = :dayOfWeek")
    fun findFacilityHoursByDay(tenantId: String, dayOfWeek: DayOfWeek): WorkingHours?

    @Query("SELECT w FROM WorkingHours w WHERE w.tenantId = :tenantId AND w.staff.id = :staffId AND w.dayOfWeek = :dayOfWeek")
    fun findByStaffIdAndDayOfWeek(tenantId: String, staffId: String, dayOfWeek: DayOfWeek): WorkingHours?

    @Query("DELETE FROM WorkingHours w WHERE w.tenantId = :tenantId AND w.staff IS NULL")
    fun deleteFacilityHours(tenantId: String)

    @Query("DELETE FROM WorkingHours w WHERE w.tenantId = :tenantId AND w.staff.id = :staffId")
    fun deleteByStaffId(tenantId: String, staffId: String)
}
