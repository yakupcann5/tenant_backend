-- Product features collection table
CREATE TABLE product_features (
    product_id VARCHAR(36) NOT NULL,
    feature VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_product_features_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_product_features_product ON product_features(product_id);

-- Blog post tags collection table
CREATE TABLE blog_post_tags (
    blog_post_id VARCHAR(36) NOT NULL,
    tag VARCHAR(255) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_blog_post_tags_blog_post FOREIGN KEY (blog_post_id) REFERENCES blog_posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_blog_post_tags_blog_post ON blog_post_tags(blog_post_id);

-- Gallery item service relation
ALTER TABLE gallery_items ADD COLUMN service_id VARCHAR(36) NULL;
ALTER TABLE gallery_items ADD CONSTRAINT fk_gallery_items_service FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE SET NULL;
CREATE INDEX idx_gallery_items_service ON gallery_items(service_id);
