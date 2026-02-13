CREATE TABLE password_reset_tokens
(
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id    VARCHAR(36)  NOT NULL,
    tenant_id  VARCHAR(36)  NOT NULL,
    token      VARCHAR(36)  NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    is_used    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_password_reset_tokens_token UNIQUE (token),
    INDEX idx_password_reset_tokens_user (user_id),
    INDEX idx_password_reset_tokens_expires (expires_at),

    CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_password_reset_tokens_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_turkish_ci;
