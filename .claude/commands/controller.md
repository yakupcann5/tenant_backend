Controller oluştur: $ARGUMENTS

## Format

`$ARGUMENTS` formatı: `EntityAdı scope:admin|public|client|staff|platform [module:MODULE_NAME]`

Örnekler:
- `Coupon scope:admin module:PRODUCTS`
- `Review scope:public`
- `Appointment scope:client`

## Kurallar

### 1. Argümanları parse et

- `EntityAdı`: İlk kelime
- `scope`: `scope:` sonrasındaki değer (admin, public, client, staff, platform)
- `module`: (opsiyonel) `module:` sonrasındaki değer (FeatureModule enum değeri)

### 2. Service dosyasını oku

`src/main/kotlin/com/aesthetic/backend/usecase/{EntityAdı}Service.kt` dosyasını oku. Service'teki metot imzalarını kullan.

### 3. Dosya konumu

`src/main/kotlin/com/aesthetic/backend/controller/{EntityAdı}Controller.kt`

### 4. Controller şablonu

```kotlin
package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.Create{Entity}Request
import com.aesthetic.backend.dto.request.Update{Entity}Request
import com.aesthetic.backend.dto.response.{Entity}Response
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.dto.response.PagedResponse
import com.aesthetic.backend.usecase.{Entity}Service
// module varsa:
// import com.aesthetic.backend.config.RequiresModule
// import com.aesthetic.backend.domain.payment.FeatureModule
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/{scope}/{feature-çoğul-kebab}")
// Scope'a göre @PreAuthorize:
//   admin → @PreAuthorize("hasAuthority('TENANT_ADMIN')")
//   client → @PreAuthorize("hasAuthority('CLIENT')")
//   staff → @PreAuthorize("hasAuthority('STAFF')")
//   platform → @PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
//   public → annotation EKLEME
// module varsa:
//   @RequiresModule(FeatureModule.{MODULE_NAME})
class {Entity}Controller(
    private val {entity}Service: {Entity}Service
) {

    @GetMapping
    fun list(pageable: Pageable): ResponseEntity<PagedResponse<{Entity}Response>> {
        val result = {entity}Service.findAll(pageable)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<ApiResponse<{Entity}Response>> {
        val result = {entity}Service.findById(id)
        return ResponseEntity.ok(ApiResponse(data = result))
    }

    @PostMapping
    fun create(
        @Valid @RequestBody request: Create{Entity}Request
    ): ResponseEntity<ApiResponse<{Entity}Response>> {
        val result = {entity}Service.create(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse(data = result))
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: Update{Entity}Request
    ): ResponseEntity<ApiResponse<{Entity}Response>> {
        val result = {entity}Service.update(id, request)
        return ResponseEntity.ok(ApiResponse(data = result))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        {entity}Service.delete(id)
        return ResponseEntity.noContent().build()
    }
}
```

### 5. Scope → Auth mapping

| Scope | @PreAuthorize | RequestMapping |
|---|---|---|
| `admin` | `@PreAuthorize("hasAuthority('TENANT_ADMIN')")` | `/api/admin/{feature}` |
| `client` | `@PreAuthorize("hasAuthority('CLIENT')")` | `/api/client/{feature}` |
| `staff` | `@PreAuthorize("hasAuthority('STAFF')")` | `/api/staff/{feature}` |
| `platform` | `@PreAuthorize("hasAuthority('PLATFORM_ADMIN')")` | `/api/platform/{feature}` |
| `public` | (yok) | `/api/public/{feature}` |

### 6. Kontrol listesi

- [ ] `@RestController` annotation var
- [ ] `@RequestMapping` doğru scope ve path ile
- [ ] `@PreAuthorize("hasAuthority('...')")` — `hasRole()` KULLANMA
- [ ] `@Valid @RequestBody` tüm POST/PUT'larda
- [ ] Response: `ApiResponse<T>` (tekil) / `PagedResponse<T>` (liste)
- [ ] Status: POST → `201 Created`, GET/PUT → `200 OK`, DELETE → `204 No Content`
- [ ] Pageable parametresi list endpoint'inde
- [ ] Module guard: `@RequiresModule` (belirtilmişse)
- [ ] Feature path kebab-case ve çoğul (`/api/admin/blog-posts`)
