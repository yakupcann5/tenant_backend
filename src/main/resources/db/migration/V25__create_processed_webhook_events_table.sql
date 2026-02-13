CREATE TABLE processed_webhook_events (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    provider VARCHAR(50) NOT NULL DEFAULT 'iyzico',
    event_type VARCHAR(100) NOT NULL DEFAULT '',
    payload JSON NULL,
    processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_processed_webhook_event_id UNIQUE (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;
