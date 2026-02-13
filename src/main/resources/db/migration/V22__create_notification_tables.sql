CREATE TABLE notification_templates (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    type VARCHAR(50) NOT NULL,
    subject VARCHAR(500) NOT NULL DEFAULT '',
    body TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_notification_templates_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uk_notification_templates_tenant_type UNIQUE (tenant_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE notifications (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    type VARCHAR(50) NOT NULL,
    recipient_id VARCHAR(36) NULL,
    recipient_email VARCHAR(255) NOT NULL DEFAULT '',
    recipient_phone VARCHAR(20) NOT NULL DEFAULT '',
    subject VARCHAR(500) NOT NULL DEFAULT '',
    body TEXT NOT NULL,
    delivery_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sent_at TIMESTAMP(6) NULL,
    error_message TEXT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_notifications_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_notifications_tenant ON notifications(tenant_id);
CREATE INDEX idx_notifications_tenant_status ON notifications(tenant_id, delivery_status);
CREATE INDEX idx_notifications_created ON notifications(created_at);
