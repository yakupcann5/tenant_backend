CREATE TABLE services (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    category_id VARCHAR(36),
    slug VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    short_description VARCHAR(500) DEFAULT '',
    description TEXT DEFAULT (''),
    price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(10) NOT NULL DEFAULT 'TRY',
    duration_minutes INT NOT NULL DEFAULT 30,
    buffer_minutes INT NOT NULL DEFAULT 0,
    image VARCHAR(500),
    recovery TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    meta_title VARCHAR(255),
    meta_description VARCHAR(500),
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_services_slug_tenant UNIQUE (slug, tenant_id),
    CONSTRAINT fk_services_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT fk_services_category FOREIGN KEY (category_id) REFERENCES service_categories(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_services_tenant ON services (tenant_id);
CREATE INDEX idx_services_tenant_active ON services (tenant_id, is_active);
CREATE INDEX idx_services_category ON services (category_id);

CREATE TABLE service_benefits (
    service_id VARCHAR(36) NOT NULL,
    benefit VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_service_benefits_service FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_service_benefits_service ON service_benefits (service_id);

CREATE TABLE service_process_steps (
    service_id VARCHAR(36) NOT NULL,
    step VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_service_process_steps_service FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_service_process_steps_service ON service_process_steps (service_id);
