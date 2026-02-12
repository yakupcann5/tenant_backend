DTO'ları oluştur: $ARGUMENTS

## Format

`$ARGUMENTS` formatı: `EntityAdı`

Örnek: `Coupon`

## Kurallar

### 1. Entity dosyasını oku

Önce entity dosyasını bul ve oku:
```
src/main/kotlin/com/aesthetic/backend/domain/**/{EntityAdı}.kt
```

Entity alanlarından DTO'ları türet.

### 2. Oluşturulacak dosyalar

4 dosya oluştur:

#### a) CreateRequest — `src/main/kotlin/com/aesthetic/backend/dto/request/Create{Entity}Request.kt`

```kotlin
package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class Create{Entity}Request(
    // Entity'deki tüm alanlar (id, createdAt, updatedAt HARİÇ)
    // Validation annotation'ları ekle:
    //   String → @field:NotBlank
    //   Email → @field:NotBlank @field:Email
    //   BigDecimal → @field:NotNull @field:PositiveOrZero
    //   Int (min) → @field:Min(0)
    //   Boolean → default değer ver, validation gereksiz
    //   Enum → @field:NotNull
    //   Ref (FK) → val staffId: String ile @field:NotBlank
    //   String? → validation ekleme (nullable)
    //   List/Set (NotEmpty) → @field:NotEmpty
    //   Şifre → @field:Password (custom validator: min 8, büyük+küçük, rakam)
)
```

#### b) UpdateRequest — `src/main/kotlin/com/aesthetic/backend/dto/request/Update{Entity}Request.kt`

```kotlin
package com.aesthetic.backend.dto.request

data class Update{Entity}Request(
    // Create ile aynı alanlar ama HEPSİ nullable (kısmi güncelleme)
    // Validation sadece null olmayan değerlere uygulanır
    //   String? → @field:Size(min = 1) (boş string engelle, null izin ver)
    //   BigDecimal? → @field:PositiveOrZero
)
```

#### c) Response — `src/main/kotlin/com/aesthetic/backend/dto/response/{Entity}Response.kt`

```kotlin
package com.aesthetic.backend.dto.response

data class {Entity}Response(
    val id: String,
    // Entity'deki tüm alanlar (şunlar HARİÇ):
    //   - passwordHash
    //   - tenantId
    //   - version
    //   - failedLoginAttempts
    //   - lockedUntil
    // FK → ilişkili entity'nin id ve name'ini ekle (entity nesnesi değil)
    //   Örn: val staffId: String, val staffName: String
    val createdAt: Instant,
    val updatedAt: Instant?
)
```

#### d) Mapper — `src/main/kotlin/com/aesthetic/backend/mapper/{Entity}Mapper.kt`

```kotlin
package com.aesthetic.backend.mapper

object {Entity}Mapper {
    fun {Entity}.toResponse(): {Entity}Response = {Entity}Response(
        id = id!!,
        // Tüm alanları map et
        // FK → entity.relatedEntity.id!!, entity.relatedEntity.name
        createdAt = createdAt!!,
        updatedAt = updatedAt
    )

    fun Create{Entity}Request.toEntity(): {Entity} = {Entity}().apply {
        // Request alanlarını entity'ye map et
        // FK → entity reference set etme, service'te repository'den bul ve set et
    }
}
```

### 3. Kontrol listesi

- [ ] Entity dosyası okundu ve alanlar doğru türetildi
- [ ] CreateRequest: tüm validation annotation'ları `@field:` prefix ile
- [ ] UpdateRequest: tüm alanlar nullable
- [ ] Response: hassas alanlar (passwordHash, tenantId, vb.) dahil edilmedi
- [ ] Response: FK'ler id + name olarak düzleştirildi
- [ ] Mapper: `toResponse()` ve `toEntity()` fonksiyonları var
- [ ] Import'lar doğru
