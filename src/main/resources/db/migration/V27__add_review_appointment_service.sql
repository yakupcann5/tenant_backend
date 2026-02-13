-- Add appointment and service relationships to reviews
ALTER TABLE reviews ADD COLUMN appointment_id VARCHAR(36) NULL;
ALTER TABLE reviews ADD COLUMN service_id VARCHAR(36) NULL;

ALTER TABLE reviews ADD CONSTRAINT fk_reviews_appointment
    FOREIGN KEY (appointment_id) REFERENCES appointments(id) ON DELETE SET NULL;

ALTER TABLE reviews ADD CONSTRAINT fk_reviews_service
    FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE SET NULL;

CREATE INDEX idx_reviews_appointment ON reviews(appointment_id);
CREATE INDEX idx_reviews_service ON reviews(service_id);
