ALTER TABLE working_hours
    ADD COLUMN break_start_time TIME NULL AFTER end_time,
    ADD COLUMN break_end_time TIME NULL AFTER break_start_time;
