CREATE TABLE blog_posts (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    title VARCHAR(500) NOT NULL,
    summary VARCHAR(1000) NOT NULL DEFAULT '',
    content TEXT NOT NULL,
    cover_image VARCHAR(500) NULL,
    is_published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMP(6) NULL,
    seo_title VARCHAR(255) NULL,
    seo_description VARCHAR(500) NULL,
    og_image VARCHAR(500) NULL,
    author_name VARCHAR(200) NOT NULL DEFAULT '',
    author_id VARCHAR(36) NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_blog_posts_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT fk_blog_posts_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uk_blog_posts_slug_tenant UNIQUE (slug, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_blog_posts_tenant ON blog_posts(tenant_id);
CREATE INDEX idx_blog_posts_tenant_published ON blog_posts(tenant_id, is_published);
