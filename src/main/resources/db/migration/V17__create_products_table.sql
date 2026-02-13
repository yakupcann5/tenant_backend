CREATE TABLE products (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    title VARCHAR(500) NOT NULL,
    short_description VARCHAR(1000) NOT NULL DEFAULT '',
    description TEXT NOT NULL,
    price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(10) NOT NULL DEFAULT 'TRY',
    image VARCHAR(500) NULL,
    stock_quantity INT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    seo_title VARCHAR(255) NULL,
    seo_description VARCHAR(500) NULL,
    og_image VARCHAR(500) NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_products_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uk_products_slug_tenant UNIQUE (slug, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_products_tenant ON products(tenant_id);
CREATE INDEX idx_products_tenant_active ON products(tenant_id, is_active);
