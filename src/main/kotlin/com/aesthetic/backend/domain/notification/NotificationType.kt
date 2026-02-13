package com.aesthetic.backend.domain.notification

enum class NotificationType {
    APPOINTMENT_CONFIRMATION,
    REMINDER_24H,
    REMINDER_1H,
    CANCELLED,
    RESCHEDULED,
    NO_SHOW_WARNING,
    BLACKLIST,
    WELCOME,
    PASSWORD_RESET,
    TRIAL_EXPIRING,
    SUBSCRIPTION_RENEWED
}
