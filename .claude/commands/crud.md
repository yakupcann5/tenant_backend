Tam CRUD oluştur: $ARGUMENTS

## Format

`$ARGUMENTS` formatı: `EntityAdı alanlar scope:admin|public|client|staff|platform [module:MODULE] [slug]`

Örnek: `Coupon code:String discount:BigDecimal expiresAt:Instant isActive:Boolean scope:admin module:PRODUCTS slug`

## Bu skill ne yapar

Tek komutla 9 dosya oluşturur:
1. **Entity** — JPA entity sınıfı
2. **Migration** — Flyway SQL dosyası
3. **Repository** — JpaRepository interface
4. **CreateRequest DTO** — Validation annotation'lı
5. **UpdateRequest DTO** — Nullable alanlar
6. **Response DTO** — Hassas alanlar hariç
7. **Mapper** — toResponse + toEntity
8. **Service** — CRUD iş mantığı
9. **Controller** — REST endpoint'ler

## Kurallar

### 1. Argümanları parse et

- `EntityAdı`: İlk kelime (PascalCase)
- `alanlar`: `alanAdi:Tip` çiftleri (Entity skill formatında)
- `scope`: `scope:` sonrasındaki değer
- `module`: (opsiyonel) `module:` sonrasındaki değer
- `slug`: (opsiyonel) slug flag'i varsa slug alanı ve UniqueConstraint ekle

### 2. Dosya konumları

```
src/main/kotlin/com/aesthetic/backend/
├── domain/{feature}/{Entity}.kt
├── repository/{Entity}Repository.kt
├── dto/request/Create{Entity}Request.kt
├── dto/request/Update{Entity}Request.kt
├── dto/response/{Entity}Response.kt
├── mapper/{Entity}Mapper.kt
├── usecase/{Entity}Service.kt
└── controller/{Entity}Controller.kt

src/main/resources/db/migration/
└── V{n}__create_{table_name}.sql
```

### 3. Oluşturma sırası

Her dosyayı oluştururken ilgili skill'in kurallarını uygula:

1. **Entity** → `/entity` skill kuralları (TenantAwareEntity, UUID, Instant, LAZY, vb.)
2. **Migration** → `/migration` skill kuralları (InnoDB, utf8mb4_turkish_ci, FK, index vb.)
3. **Repository** → JpaRepository<Entity, String> + gerekli derived query methods
4. **Request DTO'ları** → `/dto` skill kuralları (validation, nullable update)
5. **Response DTO** → `/dto` skill kuralları (hassas alan hariç)
6. **Mapper** → `/dto` skill kuralları (toResponse, toEntity)
7. **Service** → `/service` skill kuralları (@Transactional, logger, ResourceNotFoundException)
8. **Controller** → `/controller` skill kuralları (scope, @PreAuthorize, @RequiresModule, status codes)

### 4. Cross-referans kontrolü

- Controller → Service'i inject ediyor
- Service → Repository'yi inject ediyor
- Service → Mapper fonksiyonlarını kullanıyor
- Service → Request DTO alıyor, Response DTO döndürüyor
- Mapper → Entity ve DTO'lar arası doğru mapping yapıyor
- Repository → Entity tipi doğru
- Migration → Entity alanlarıyla birebir uyumlu

### 5. Slug desteği (flag varsa)

Entity'ye ekle:
```kotlin
var slug: String = ""
```

Migration'a ekle:
```sql
slug VARCHAR(100) NOT NULL,
UNIQUE KEY uk_{tablo}_slug_tenant (slug, tenant_id)
```

### 6. Kontrol listesi

- [ ] Tüm 9 dosya oluşturuldu
- [ ] Entity → TenantAwareEntity, UUID id, Instant timestamp, LAZY fetch
- [ ] Migration → InnoDB, utf8mb4_turkish_ci, FK CASCADE, doğru tipler
- [ ] Repository → JpaRepository<Entity, String>
- [ ] CreateRequest → @field: validation annotation'ları
- [ ] UpdateRequest → nullable alanlar
- [ ] Response → hassas alanlar hariç, FK düzleştirilmiş
- [ ] Mapper → toResponse + toEntity doğru mapping
- [ ] Service → @Transactional, logger, ResourceNotFoundException, DTO mapping
- [ ] Controller → doğru scope, @PreAuthorize, @Valid, status kodları
- [ ] Cross-referanslar → tüm dosyalar birbirine doğru referans veriyor
- [ ] Import'lar → tüm dosyalarda doğru
