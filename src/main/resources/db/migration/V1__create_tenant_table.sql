CREATE TABLE tenants (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    slug VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    business_type ENUM('BEAUTY_CLINIC','DENTAL_CLINIC','BARBER_SHOP','HAIR_SALON','DIETITIAN','PHYSIOTHERAPIST','MASSAGE_SALON','VETERINARY','GENERAL') NOT NULL,
    phone VARCHAR(20) DEFAULT '',
    email VARCHAR(255) DEFAULT '',
    address TEXT DEFAULT (''),
    logo_url VARCHAR(500),
    custom_domain VARCHAR(255),
    plan ENUM('TRIAL','STARTER','PROFESSIONAL','BUSINESS','ENTERPRISE') DEFAULT 'TRIAL',
    trial_end_date DATE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_tenants_slug UNIQUE (slug),
    CONSTRAINT uk_tenants_custom_domain UNIQUE (custom_domain)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_tenants_is_active ON tenants (is_active);
CREATE INDEX idx_tenants_plan ON tenants (plan);
