CREATE TABLE reviews (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    client_id VARCHAR(36) NULL,
    client_name VARCHAR(200) NOT NULL DEFAULT '',
    rating INT NOT NULL,
    comment TEXT NOT NULL,
    approval_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    admin_response TEXT NULL,
    admin_response_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_reviews_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT fk_reviews_client FOREIGN KEY (client_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_reviews_tenant ON reviews(tenant_id);
CREATE INDEX idx_reviews_tenant_status ON reviews(tenant_id, approval_status);
