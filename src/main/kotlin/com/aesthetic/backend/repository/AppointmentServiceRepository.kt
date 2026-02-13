package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.appointment.AppointmentServiceEntity
import org.springframework.data.jpa.repository.JpaRepository

interface AppointmentServiceRepository : JpaRepository<AppointmentServiceEntity, String>
