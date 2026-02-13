-- V12: Add autoConfirmAppointments to site_settings and ogImage to services

ALTER TABLE site_settings
    ADD COLUMN auto_confirm_appointments BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE services
    ADD COLUMN og_image VARCHAR(500) NULL;
