CREATE TABLE gallery_items (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    title VARCHAR(500) NOT NULL DEFAULT '',
    description TEXT NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    before_image_url VARCHAR(500) NULL,
    after_image_url VARCHAR(500) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    category VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_gallery_items_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_gallery_items_tenant ON gallery_items(tenant_id);
CREATE INDEX idx_gallery_items_tenant_active ON gallery_items(tenant_id, is_active);
