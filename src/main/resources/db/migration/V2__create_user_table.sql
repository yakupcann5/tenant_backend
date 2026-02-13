CREATE TABLE users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    phone VARCHAR(20) DEFAULT '',
    image VARCHAR(500),
    title VARCHAR(100),
    role ENUM('PLATFORM_ADMIN','TENANT_ADMIN','STAFF','CLIENT') NOT NULL DEFAULT 'CLIENT',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    force_password_change BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP(6) NULL,
    no_show_count INT NOT NULL DEFAULT 0,
    is_blacklisted BOOLEAN NOT NULL DEFAULT FALSE,
    blacklisted_at TIMESTAMP(6) NULL,
    blacklist_reason VARCHAR(500),
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_users_email_tenant UNIQUE (email, tenant_id),
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_users_tenant_id ON users (tenant_id);
CREATE INDEX idx_users_tenant_role ON users (tenant_id, role);
