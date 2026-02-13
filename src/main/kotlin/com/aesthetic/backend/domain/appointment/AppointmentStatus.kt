package com.aesthetic.backend.domain.appointment

enum class AppointmentStatus {
    PENDING,
    CONFIRMED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    NO_SHOW;

    fun canTransitionTo(target: AppointmentStatus): Boolean = when (this) {
        PENDING -> target in listOf(CONFIRMED, CANCELLED)
        CONFIRMED -> target in listOf(IN_PROGRESS, CANCELLED, NO_SHOW)
        IN_PROGRESS -> target == COMPLETED
        COMPLETED, CANCELLED, NO_SHOW -> false
    }
}
