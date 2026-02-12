Migration dosyası oluştur: $ARGUMENTS

## Format

`$ARGUMENTS` formatı: `tablo_adı` veya `değişiklik_açıklaması`

Örnekler:
- `create_coupons`
- `add_discount_column_to_services`
- `create_staff_services`

## Kurallar

### 1. Versiyon numarası belirleme

Mevcut migration dosyalarını tara:
```
src/main/resources/db/migration/V*__*.sql
```
En yüksek V numarasını bul ve bir sonrakini kullan. Örn: V16 varsa → V17.

### 2. Dosya adı formatı

`V{n}__{aciklama}.sql` — çift alt çizgi zorunlu.

Dosya konumu: `src/main/resources/db/migration/V{n}__{aciklama}.sql`

### 3. CREATE TABLE şablonu

```sql
CREATE TABLE {tablo_adı} (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,

    -- Alanlar buraya (argümandan veya entity'den türet)

    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_{tablo}_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- Index'ler
CREATE INDEX idx_{tablo}_tenant ON {tablo_adı}(tenant_id);
-- Sık sorgulanan alanlar için ek index
```

### 4. Tip eşlemeleri (SQL)

| Kotlin tipi | SQL tipi |
|---|---|
| `String` (kısa) | `VARCHAR(255) NOT NULL DEFAULT ''` |
| `String` (slug, code) | `VARCHAR(100) NOT NULL` |
| `String?` | `VARCHAR(255) DEFAULT NULL` |
| `BigDecimal` | `DECIMAL(10,2) NOT NULL DEFAULT 0.00` |
| `Int` | `INT NOT NULL DEFAULT 0` |
| `Boolean` | `BOOLEAN NOT NULL DEFAULT FALSE` veya `TRUE` |
| `Instant` | `TIMESTAMP(6) DEFAULT NULL` |
| `LocalDate` | `DATE NOT NULL` |
| `LocalTime` | `TIME DEFAULT NULL` |
| `Text` / `TEXT` | `TEXT` |
| `Json` / `JSON` | `JSON` |
| `Enum` | `VARCHAR(50) NOT NULL` |
| `FK (zorunlu)` | `VARCHAR(36) NOT NULL` + `FOREIGN KEY ... ON DELETE CASCADE` |
| `FK (opsiyonel)` | `VARCHAR(36) DEFAULT NULL` + `FOREIGN KEY ... ON DELETE SET NULL` |

### 5. @ElementCollection join tabloları

`@ElementCollection` kullanan entity'lerin join tablolarına `sort_order` sütunu ekle:

```sql
sort_order INT NOT NULL DEFAULT 0
```

### 6. Entity'den alan türetme

Eğer entity dosyası mevcutsa (`src/main/kotlin/com/aesthetic/backend/domain/**/*.kt`), önce entity'yi oku ve alanları oradan türet. Alan tekrar belirtmeye gerek kalmasın.

### 7. ALTER TABLE şablonu

```sql
ALTER TABLE {tablo_adı}
    ADD COLUMN {sütun_adı} {TİP} {CONSTRAINT};

CREATE INDEX idx_{tablo}_{sütun} ON {tablo_adı}({sütun_adı});
```

### 8. Kontrol listesi

- [ ] Versiyon numarası doğru (mevcut en yüksek + 1)
- [ ] Dosya adı `V{n}__{aciklama}.sql` formatında (çift alt çizgi)
- [ ] `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci`
- [ ] ID: `VARCHAR(36) NOT NULL PRIMARY KEY`
- [ ] tenant_id: `VARCHAR(36) NOT NULL` + FK `ON DELETE CASCADE`
- [ ] Timestamp: `TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6)`
- [ ] Boolean: `BOOLEAN NOT NULL DEFAULT TRUE/FALSE`
- [ ] Para: `DECIMAL(10,2) NOT NULL DEFAULT 0.00`
- [ ] Index adlandırma: `idx_{tablo}_{sütunlar}` / `uk_{tablo}_{sütunlar}`
- [ ] Unique constraint tenant bazlı: `UNIQUE KEY uk_{tablo}_{alan}_tenant ({alan}, tenant_id)`
- [ ] FK'ler: zorunlu → `ON DELETE CASCADE`, opsiyonel → `ON DELETE SET NULL`
