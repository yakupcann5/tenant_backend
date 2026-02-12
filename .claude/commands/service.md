Service oluştur: $ARGUMENTS

## Format

`$ARGUMENTS` formatı: `EntityAdı` veya `FeatureAdı`

Örnek: `Coupon`

## Kurallar

### 1. Entity ve DTO dosyalarını oku

Önce ilgili dosyaları bul ve oku:
- Entity: `src/main/kotlin/com/aesthetic/backend/domain/**/{EntityAdı}.kt`
- Repository: `src/main/kotlin/com/aesthetic/backend/repository/{EntityAdı}Repository.kt`
- DTOs: `src/main/kotlin/com/aesthetic/backend/dto/request/*{EntityAdı}*.kt`
- Response: `src/main/kotlin/com/aesthetic/backend/dto/response/{EntityAdı}Response.kt`
- Mapper: `src/main/kotlin/com/aesthetic/backend/mapper/{EntityAdı}Mapper.kt`

Repository yoksa oluştur.

### 2. Dosya konumu

`src/main/kotlin/com/aesthetic/backend/usecase/{EntityAdı}Service.kt`

### 3. Service şablonu

```kotlin
package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.{feature}.{Entity}
import com.aesthetic.backend.repository.{Entity}Repository
import com.aesthetic.backend.dto.request.Create{Entity}Request
import com.aesthetic.backend.dto.request.Update{Entity}Request
import com.aesthetic.backend.dto.response.{Entity}Response
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.mapper.{Entity}Mapper.toResponse
import com.aesthetic.backend.mapper.{Entity}Mapper.toEntity
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class {Entity}Service(
    private val {entity}Repository: {Entity}Repository,
    // Gerekli ek servisler (FK'ler için repository'ler vb.)
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun findById(id: String): {Entity}Response {
        val entity = {entity}Repository.findById(id)
            .orElseThrow { ResourceNotFoundException("{Entity} bulunamadı: $id") }
        return entity.toResponse()
    }

    fun findAll(pageable: Pageable): PagedResponse<{Entity}Response> {
        val page = {entity}Repository.findAll(pageable)
        return PagedResponse(
            data = page.content.map { it.toResponse() },
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages
        )
    }

    @Transactional
    fun create(request: Create{Entity}Request): {Entity}Response {
        val entity = request.toEntity()
        // FK ilişkilerini repository'den bul ve set et
        // tenant_id TenantEntityListener tarafından otomatik set edilir
        val saved = {entity}Repository.save(entity)
        logger.info("{Entity} oluşturuldu: ${saved.id}")
        return saved.toResponse()
    }

    @Transactional
    fun update(id: String, request: Update{Entity}Request): {Entity}Response {
        val entity = {entity}Repository.findById(id)
            .orElseThrow { ResourceNotFoundException("{Entity} bulunamadı: $id") }

        // Nullable alanları kontrol ederek güncelle
        // request.field?.let { entity.field = it }

        val updated = {entity}Repository.save(entity)
        logger.info("{Entity} güncellendi: $id")
        return updated.toResponse()
    }

    @Transactional
    fun delete(id: String) {
        val entity = {entity}Repository.findById(id)
            .orElseThrow { ResourceNotFoundException("{Entity} bulunamadı: $id") }
        {entity}Repository.delete(entity)
        logger.info("{Entity} silindi: $id")
    }
}
```

### 4. Repository oluşturma (yoksa)

```kotlin
package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.{feature}.{Entity}
import org.springframework.data.jpa.repository.JpaRepository

interface {Entity}Repository : JpaRepository<{Entity}, String> {
    // Derived query methods (gerekirse)
    // fun findBySlugAndTenantId(slug: String, tenantId: String): {Entity}?
}
```

### 5. Ek kurallar

- **Bildirim gönderimi:** Bildirim gereken işlemlerde `notificationService.toContext(entity)` ile `NotificationContext` DTO oluştur, sonra async gönder. `@Async` metotlarda Entity KULLANMA
- **Plan limiti kontrolü:** Modül gerektiren işlemlerde `moduleAccessService.requireAccess(tenantId, module)` çağır
- **Self-invocation dikkat:** Aynı sınıftan `@Transactional` metot çağrısı proxy'yi bypass eder. Farklı bean'den çağır veya `TransactionTemplate` kullan
- **Tenant timezone:** Tarih/saat hesaplamalarında `val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")` kullan

### 6. Kontrol listesi

- [ ] `@Service` annotation var
- [ ] Yazma metotlarında `@Transactional` var
- [ ] Logger tanımlı: `LoggerFactory.getLogger(javaClass)`
- [ ] `findById` → `.orElseThrow { ResourceNotFoundException(...) }`
- [ ] Entity ASLA döndürülmüyor → Response DTO mapping yapılıyor
- [ ] Constructor injection kullanılıyor (field injection değil)
- [ ] `tenant_id` manuel set EDİLMİYOR (TenantEntityListener otomatik)
- [ ] FK ilişkileri repository'den bulunup set ediliyor
- [ ] Bildirim gereken işlemlerde `toContext()` pattern kullanılıyor
- [ ] Plan limiti kontrolü gerekiyorsa `moduleAccessService.requireAccess()` çağrılıyor
- [ ] Import'lar doğru
