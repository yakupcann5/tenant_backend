package com.aesthetic.backend.domain.appointment

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AppointmentStatusTest {

    @Test
    fun `PENDING can transition to CONFIRMED`() {
        assertTrue(AppointmentStatus.PENDING.canTransitionTo(AppointmentStatus.CONFIRMED))
    }

    @Test
    fun `PENDING can transition to CANCELLED`() {
        assertTrue(AppointmentStatus.PENDING.canTransitionTo(AppointmentStatus.CANCELLED))
    }

    @Test
    fun `PENDING cannot transition to IN_PROGRESS`() {
        assertFalse(AppointmentStatus.PENDING.canTransitionTo(AppointmentStatus.IN_PROGRESS))
    }

    @Test
    fun `PENDING cannot transition to COMPLETED`() {
        assertFalse(AppointmentStatus.PENDING.canTransitionTo(AppointmentStatus.COMPLETED))
    }

    @Test
    fun `CONFIRMED can transition to IN_PROGRESS`() {
        assertTrue(AppointmentStatus.CONFIRMED.canTransitionTo(AppointmentStatus.IN_PROGRESS))
    }

    @Test
    fun `CONFIRMED can transition to CANCELLED`() {
        assertTrue(AppointmentStatus.CONFIRMED.canTransitionTo(AppointmentStatus.CANCELLED))
    }

    @Test
    fun `CONFIRMED can transition to NO_SHOW`() {
        assertTrue(AppointmentStatus.CONFIRMED.canTransitionTo(AppointmentStatus.NO_SHOW))
    }

    @Test
    fun `CONFIRMED cannot transition to COMPLETED directly`() {
        assertFalse(AppointmentStatus.CONFIRMED.canTransitionTo(AppointmentStatus.COMPLETED))
    }

    @Test
    fun `IN_PROGRESS can transition to COMPLETED`() {
        assertTrue(AppointmentStatus.IN_PROGRESS.canTransitionTo(AppointmentStatus.COMPLETED))
    }

    @Test
    fun `IN_PROGRESS cannot transition to CANCELLED`() {
        assertFalse(AppointmentStatus.IN_PROGRESS.canTransitionTo(AppointmentStatus.CANCELLED))
    }

    @Test
    fun `COMPLETED is terminal - no transitions allowed`() {
        AppointmentStatus.entries.forEach { target ->
            assertFalse(AppointmentStatus.COMPLETED.canTransitionTo(target))
        }
    }

    @Test
    fun `CANCELLED is terminal - no transitions allowed`() {
        AppointmentStatus.entries.forEach { target ->
            assertFalse(AppointmentStatus.CANCELLED.canTransitionTo(target))
        }
    }

    @Test
    fun `NO_SHOW is terminal - no transitions allowed`() {
        AppointmentStatus.entries.forEach { target ->
            assertFalse(AppointmentStatus.NO_SHOW.canTransitionTo(target))
        }
    }
}
