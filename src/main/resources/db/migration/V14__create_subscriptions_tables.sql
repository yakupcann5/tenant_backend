CREATE TABLE subscriptions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    plan VARCHAR(20) NOT NULL DEFAULT 'TRIAL',
    status VARCHAR(20) NOT NULL DEFAULT 'TRIAL',
    billing_period VARCHAR(10) NOT NULL DEFAULT 'MONTHLY',
    current_period_start TIMESTAMP(6) NULL,
    current_period_end TIMESTAMP(6) NULL,
    trial_end_date DATE NULL,
    pending_plan_change VARCHAR(20) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_retry_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_subscriptions_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uk_subscriptions_tenant UNIQUE (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE subscription_modules (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    subscription_id VARCHAR(36) NOT NULL,
    module VARCHAR(30) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    tenant_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_sub_modules_subscription FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE,
    CONSTRAINT fk_sub_modules_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uk_sub_modules_subscription_module UNIQUE (subscription_id, module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_subscriptions_status ON subscriptions(status);
CREATE INDEX idx_subscriptions_trial_end ON subscriptions(status, trial_end_date);
CREATE INDEX idx_sub_modules_subscription ON subscription_modules(subscription_id);
