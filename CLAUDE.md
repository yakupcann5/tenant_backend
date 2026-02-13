# Aesthetic SaaS Backend — Proje Kuralları

Bu dosya, tüm geliştirme sürecinde uyulması gereken kuralları tanımlar. Her kural **neden** o şekilde olduğunu açıklar. Mimari detaylar için `BACKEND_ARCHITECTURE.md` referans alınır.

---

## 1. Proje Kimliği

- **Tech Stack:** Kotlin 2.0+, Spring Boot 3.4+, MySQL 8.0, Redis 7, Java 21, Gradle Kotlin DSL
- **Mimari:** Multi-Tenant SaaS — Shared DB + Discriminator Column (`tenant_id`), Hibernate `@Filter`
- **Base package:** `com.aesthetic.backend`
- **Mimari referans:** `BACKEND_ARCHITECTURE.md`

### Frontend Dokümantasyon Senkronizasyonu (KRİTİK)

Backend'de aşağıdaki değişiklikler yapıldığında `FRONTEND_ARCHITECTURE.md` ve/veya `CLAUDE_FRONTEND.md` de güncellenmelidir:

| Backend Değişikliği | Güncellenecek Frontend Dokümanı | İlgili Bölüm |
|---------------------|--------------------------------|---------------|
| Yeni/değişen API endpoint | FRONTEND_ARCHITECTURE.md | §25 Backend ile Entegrasyon Referansı |
| DTO ekleme/değiştirme (Request/Response) | FRONTEND_ARCHITECTURE.md | §5 API Client + ilgili feature bölümü |
| Enum ekleme/değiştirme (ErrorCode, Status, vb.) | FRONTEND_ARCHITECTURE.md | §5.2 + §8.1 + ilgili bölüm |
| Yeni FeatureModule | FRONTEND_ARCHITECTURE.md | §8 Modül Sistemi |
| SiteSettings alanı ekleme | FRONTEND_ARCHITECTURE.md | §2.4 Tenant Config + §10 Tema |
| Yeni BusinessType | FRONTEND_ARCHITECTURE.md | §9 Terminoloji + §10 Tema |
| Appointment status geçiş kuralı değişikliği | FRONTEND_ARCHITECTURE.md | §16 Randevu Sistemi |
| Yeni güvenlik kuralı / CORS değişikliği | CLAUDE_FRONTEND.md | §8 Auth + §12 Güvenlik |
| Plan limiti / modül değişikliği | FRONTEND_ARCHITECTURE.md | §28 Ödeme & Abonelik |

*WHY: Frontend ve backend dokümantasyonları birebir senkron olmalıdır. Backend'de eklenen bir endpoint frontend dokümanında eksikse, frontend geliştirici o endpoint'i bilmez ve kullanmaz. Enum uyumsuzluğu runtime hatalarına yol açar.*

---

## 2. Paket Yapısı

```
com.aesthetic.backend/
├── config/          → Security, JWT, Async, CORS, Cache, Flyway, OpenAPI, ShedLock, RateLimit
├── tenant/          → Multi-tenant altyapısı (Context, Filter, Aspect, Entity, Listener, Decorator, Cache)
├── security/        → JWT provider, auth filter, UserPrincipal
├── domain/{feature}/ → Entity sınıfları
├── repository/      → JpaRepository interface'leri
├── usecase/         → İş mantığı servisleri ({Feature}Service)
├── controller/      → REST controller'lar ({Feature}Controller)
├── dto/request/     → Create/Update request DTO'ları
├── dto/response/    → Response DTO'ları + ApiResponse, PagedResponse, ErrorResponse
├── mapper/          → Entity↔DTO dönüşüm extension function'ları
├── job/             → Scheduled job'lar (reminder, trial expiration, cleanup)
├── exception/       → Domain-specific exception sınıfları
```

---

## 3. Multi-Tenant Kuralları (KRİTİK)

### 3.1 TenantAwareEntity extend zorunlu

Tüm tenant-scoped entity'ler `TenantAwareEntity`'den extend etmelidir. — *WHY: `@FilterDef`, `@Filter` ve `@EntityListeners` annotation'ları miras yoluyla otomatik uygulanır. Extend etmeyen entity'lerde SELECT filtreleme ve INSERT/UPDATE/DELETE koruması ÇALIŞMAZ.*

```kotlin
@FilterDef(name = "tenantFilter", parameters = [ParamDef(name = "tenantId", type = "string")])
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@EntityListeners(TenantEntityListener::class)
@MappedSuperclass
abstract class TenantAwareEntity {
    @Column(name = "tenant_id", nullable = false, updatable = false)
    lateinit var tenantId: String
}
```

**İstisnalar** (TenantAwareEntity extend ETMEZ): `Tenant`, `RefreshToken`, `AuditLog`, `ProcessedWebhookEvent`
- *WHY: Tenant → çözümleme sırasında context yoktur. RefreshToken → token rotation'da tenant context olmayabilir. AuditLog → platform admin cross-tenant görünürlük gerektirir. ProcessedWebhookEvent → webhook'lar tenant context dışında çalışır.*

### 3.2 ThreadLocal (InheritableThreadLocal DEĞİL)

```kotlin
object TenantContext {
    private val currentTenant = ThreadLocal<String>()  // InheritableThreadLocal DEĞİL!
    // ...
}
```

*WHY: `InheritableThreadLocal` thread pool'larda tehlikelidir — thread yeniden kullanıldığında eski parent thread'in tenant bilgisi kalır, yanlış tenant'a veri sızıntısı riski oluşur. Async propagation için `TenantAwareTaskDecorator` kullanılır.*

### 3.3 tenant_id AUTO-SET — Manuel set YAPMA

*WHY: `TenantEntityListener.onPrePersist` tenant_id'yi `TenantContext`'ten otomatik set eder. Manuel set cross-tenant yazma saldırısı riski oluşturur — listener zaten farklı tenant_id set edilmeye çalışılırsa `SecurityException` fırlatır.*

### 3.4 Hibernate @Filter sadece SELECT'i korur

*WHY: `@Filter` annotation'ı sadece SELECT sorgularına WHERE koşulu ekler. INSERT/UPDATE/DELETE için `TenantEntityListener` (@PrePersist, @PreUpdate, @PreRemove) ayrıca koruma sağlar.*

### 3.5 TenantAspect hariç tutmaları

```kotlin
@Before(
    "execution(* com.aesthetic.backend.repository..*.*(..)) " +
    "&& !execution(* com.aesthetic.backend.repository.TenantRepository.*(..)) " +
    "&& !execution(* com.aesthetic.backend.repository.RefreshTokenRepository.*(..))"
)
fun enableTenantFilter() {
    val tenantId = TenantContext.getTenantIdOrNull() ?: return  // Platform admin bypass
    // ...
}
```

- **TenantRepository hariç** — *WHY: `TenantFilter` içinde tenant çözümleme SIRASINDA çağrılır, henüz `TenantContext` set edilmemiştir.*
- **RefreshTokenRepository hariç** — *WHY: Token rotation'da tenant context olmayabilir.*
- **Platform admin bypass** — *WHY: `getTenantIdOrNull()` null dönerse filter aktifleştirilmez, PLATFORM_ADMIN tüm verilere erişir.*

### 3.6 Cache key tenant-scoped

Format: `tenant:{tenantId}:{methodName}:{key}` — *WHY: Tenant-scoped olmayan cache key'ler farklı tenant'ların verisini karıştırır.*

```kotlin
// DOĞRU: tenant-scoped key generator kullan
@Cacheable(value = ["services"], keyGenerator = "tenantCacheKeyGenerator")
fun getActiveServices(): List<Service> { ... }

// YANLIŞ: allEntries=true KULLANMA — tüm tenant'ların cache'ini siler!
// @CacheEvict(value = ["services"], allEntries = true)  // ← YAPMA

// DOĞRU: tenant-scoped eviction
@CacheEvict(value = ["services"], keyGenerator = "tenantCacheKeyGenerator")
fun createService(request: CreateServiceRequest): Service { ... }

// Bir tenant'ın TÜM cache'ini silmek gerekirse:
tenantCacheManager.evictAllForCurrentTenant("services")  // Redis pattern: tenant:{id}:services:*
```

### 3.7 Async propagation

```kotlin
class TenantAwareTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        val tenantId = TenantContext.getTenantIdOrNull()
        // KRİTİK: SecurityContext KOPYALANMALI, referans paylaşılmamalı!
        val clonedContext = SecurityContextHolder.createEmptyContext().apply {
            authentication = SecurityContextHolder.getContext().authentication
        }
        return Runnable {
            try {
                tenantId?.let { TenantContext.setTenantId(it) }
                SecurityContextHolder.setContext(clonedContext)
                runnable.run()
            } finally {
                TenantContext.clear()
                SecurityContextHolder.clearContext()
            }
        }
    }
}
```

*WHY: `ThreadLocal` `@Async`'te propagate OLMAZ. `TenantAwareTaskDecorator`, `AsyncConfig`'teki executor'a kayıtlıdır ve SecurityContext'i KOPYALAR (referans paylaşımı parent thread değişikliklerini child'a sızdırır).*

---

## 4. Entity Kuralları

- **ID:** `@Id @GeneratedValue(strategy = GenerationType.UUID) val id: String? = null`
- **Tablo adı:** snake_case çoğul (`blog_posts`, `service_categories`)
- **Tarih/Zaman:** `Instant` (UTC) — *WHY: `LocalDateTime` server timezone'una bağımlıdır, farklı sunucularda farklı sonuç verir*
- **Sadece tarih:** `LocalDate` (randevu tarihi, tedavi tarihi)
- **Saat:** `LocalTime` (randevu başlangıç/bitiş)
- **Fiyat:** `@Column(precision = 10, scale = 2) var price: BigDecimal = BigDecimal.ZERO` — *WHY: `Double`'da `0.1 + 0.2 ≠ 0.3` floating point hatası*
- **Para birimi:** `var currency: String = "TRY"`
- **Timestamp:** `@CreationTimestamp val createdAt: Instant? = null` / `@UpdateTimestamp var updatedAt: Instant? = null`
- **Enum:** `@Enumerated(EnumType.STRING)` — *WHY: `ORDINAL` sıra değişikliğinde veri bozulur*
- **İlişki:** `@ManyToOne(fetch = FetchType.LAZY)` — *WHY: `EAGER` N+1 query problemine ve gereksiz JOIN'lere yol açar*
- **@ElementCollection:** Her zaman `@OrderColumn(name = "sort_order")` ekle — *WHY: Sıralama garanti altına alınır*
- **UniqueConstraint:** Tenant bazlı → `columnNames = ["field", "tenant_id"]`
- **Boolean:** `var isActive: Boolean = true` — nullable olmasın
- **JSON:** `@Column(columnDefinition = "JSON") var data: String = "{}"`
- **TEXT:** `@Column(columnDefinition = "TEXT") var content: String = ""`
- **Slug:** `var slug: String = ""` + `UniqueConstraint(name = "uk_{entity}_slug_tenant", columnNames = ["slug", "tenant_id"])`

### Snapshot Pattern

İlişkili entity'den kopyalanan alanlar (örn: `Appointment.clientName`, `clientEmail`, `clientPhone`). — *WHY: Müşteri bilgisi sonradan değişse bile randevu kaydındaki versi korunur. Raporlama ve fatura doğruluğu için kritik.*

---

## 5. Repository Kuralları

- `JpaRepository<Entity, String>` extend et
- Derived query: `findByFieldAndTenantId()`, `countByTenantIdAndRole()`
- Custom JPQL: `@Query("SELECT e FROM Entity e WHERE ...")`
- Enum param: `@Param` ile geç — *WHY: String literal JPQL'de tip güvenliği yoktur ve refactoring'de kırılır*

```kotlin
// DOĞRU:
@Query("SELECT a FROM Appointment a WHERE a.status = :status")
fun findByStatus(@Param("status") status: AppointmentStatus): List<Appointment>

// YANLIŞ:
// @Query("SELECT a FROM Appointment a WHERE a.status = 'PENDING'")  // ← string literal
```

- **Pessimistic lock:** `@Lock(LockModeType.PESSIMISTIC_WRITE)` — *WHY: `SERIALIZABLE` isolation MySQL InnoDB'de deadlock'a neden olur. SERIALIZABLE tüm SELECT'leri `LOCK IN SHARE MODE`'a çevirir, sonra `FOR UPDATE`'e yükseltme girişiminde karşılıklı bloklanma oluşur. Doğru: `READ_COMMITTED` + `PESSIMISTIC_WRITE` (SELECT ... FOR UPDATE).*

```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
    SELECT a FROM Appointment a
    WHERE a.tenantId = :tenantId AND a.date = :date AND a.staffId = :staffId
    AND a.status NOT IN (:excludeStatuses)
    AND a.startTime < :endTime AND a.endTime > :startTime
""")
fun findConflictingAppointments(/* params */): List<Appointment>
```

- **GDPR:** `@Modifying @Query("UPDATE ... SET ...")` anonimleştirme metotları

---

## 6. Service Kuralları

- `@Service` + `@Transactional` (yazma işlemleri)
- Logger: `private val logger = LoggerFactory.getLogger(javaClass)`
- findById: `.orElseThrow { ResourceNotFoundException("... bulunamadı: $id") }`
- **Entity ASLA döndürme** → Response DTO mapping yap — *WHY: Hassas alan sızıntısı (passwordHash, tenantId), döngüsel referans, lazy proxy serialization hatası*
- **Bildirim:** `notificationService.toContext(entity)` → async gönder — *WHY: @Async metotlarda Entity proxy kullanmak `LazyInitializationException` fırlatır, session kapalıdır*
- **Plan limiti:** `planLimitService.checkCanCreateAppointment(tenantId)` veya `moduleAccessService.requireAccess(tenantId, module)`
- **Tenant timezone:** `val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")` — *WHY: Her tenant farklı timezone'da olabilir*
- **Self-invocation dikkat:** Aynı sınıftan `@Transactional` çağrısı proxy'yi bypass eder — *WHY: Spring AOP proxy mekanizması, `this.method()` çağrısında AOP advice uygulanmaz. Farklı bean'den çağır.*

### entityManager.getReference() Pattern

```kotlin
// DOĞRU: FK set ederken getReference() kullan — DB'ye SELECT gitmez, lazy proxy döner
this.staff = entityManager.getReference(User::class.java, staffId)

// YANLIŞ: findById() ile gereksiz SELECT sorgusu
// this.staff = userRepository.findById(staffId).orElseThrow { ... }  // ← sadece FK için gereksiz
```

*WHY: `getReference()` Hibernate proxy döndürür, sadece ID bilgisiyle FK ilişkisi kurulur. `findById()` ise tüm entity'yi DB'den çeker — sadece FK atamak için gereksiz bir SELECT.*

---

## 7. Controller & DTO Kuralları

### Scope → Auth Mapping

| Scope | Auth | Açıklama |
|-------|------|----------|
| `/api/public/**` | `permitAll` | Subdomain'den tenant çözümlenir |
| `/api/auth/**` | `permitAll` | Login, register, refresh, forgot/reset-password |
| `/api/admin/**` | `hasAuthority('TENANT_ADMIN')` | Tenant yönetim |
| `/api/client/**` | `hasAuthority('CLIENT')` | Müşteri işlemleri |
| `/api/staff/**` | `hasAuthority('STAFF')` | Personel (read-only kısıtlı) |
| `/api/platform/**` | `hasAuthority('PLATFORM_ADMIN')` | Platform yönetim (tenant context yok) |
| `/api/webhooks/**` | `permitAll` | Harici servis callback'leri |

- `@PreAuthorize("hasAuthority('ROLE')")` kullan — *WHY: `hasRole()` otomatik `ROLE_` prefix ekler, DB'de `TENANT_ADMIN` saklandığı için eşleşmez*
- `@Valid @RequestBody` zorunlu
- Status: `201 Created` (POST), `200 OK` (GET/PUT/PATCH), `204 No Content` (DELETE)
- Pageable: `fun list(pageable: Pageable): PagedResponse<T>`

### @RequiresModule + ModuleGuardInterceptor

```kotlin
@RestController
@RequestMapping("/api/admin/blog")
@RequiresModule(FeatureModule.BLOG)  // Class-level: tüm endpoint'ler BLOG modülü gerektirir
class BlogAdminController { ... }
```

*Akış: HTTP Request → `ModuleGuardInterceptor.preHandle()` → `@RequiresModule` annotation kontrol → `moduleAccessService.requireAccess(tenantId, module)` → TRIAL ise tüm modüller açık, değilse `SubscriptionModule` tablosunda kontrol → erişim yoksa `PlanLimitExceededException`*

### DTO Kuralları

- Request: `data class Create{Entity}Request(...)` / `Update{Entity}Request(...)`
- Response: `data class {Entity}Response(...)`
- Mapper: `fun Entity.toResponse(): EntityResponse` extension function
- Validation: `@field:NotBlank`, `@field:Email`, `@field:Min(n)`, `@field:PositiveOrZero`, `@field:Password` (custom)
  — *WHY: `@field:` prefix zorunlu, Kotlin data class'larda annotation target belirsizdir (constructor param mı, field mı?)*
- Response'ta **OLMAYACAKLAR**: `passwordHash`, `tenantId`, `version`, `failedLoginAttempts`, `lockedUntil`

---

## 8. Response Format & Error Handling

### Response Tipleri

- **Başarılı:** `ApiResponse<T>` — `success`, `data`, `message`, `timestamp`
- **Sayfalı:** `PagedResponse<T>` — `success`, `data`, `page`, `size`, `totalElements`, `totalPages`, `timestamp`
- **Hata:** `ErrorResponse` — `success=false`, `error`, `code: ErrorCode`, `details: Map<String, String>?`, `timestamp`

### Exception → HTTP Status Mapping

| Exception | Status | ErrorCode |
|-----------|--------|-----------|
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` |
| `IllegalArgumentException` | 400 | — |
| `AuthenticationException` | 401 | `INVALID_CREDENTIALS` |
| `AccessDeniedException` | 403 | `FORBIDDEN` |
| `PlanLimitExceededException` | 403 | `PLAN_LIMIT_EXCEEDED` |
| `ClientBlacklistedException` | 403 | `CLIENT_BLACKLISTED` |
| `ResourceNotFoundException` | 404 | `RESOURCE_NOT_FOUND` |
| `TenantNotFoundException` | 404 | `TENANT_NOT_FOUND` |
| `AppointmentConflictException` | 409 | `APPOINTMENT_CONFLICT` |
| `DataIntegrityViolationException` | 409 | `DUPLICATE_RESOURCE` |
| `AccountLockedException` | 423 | `ACCOUNT_LOCKED` |
| `Exception` (fallback) | 500 | `INTERNAL_ERROR` |

### Validation Error Format

```json
{
  "success": false,
  "error": "Doğrulama hatası",
  "code": "VALIDATION_ERROR",
  "details": {
    "email": "Geçerli bir e-posta adresi giriniz",
    "name": "Bu alan boş olamaz"
  }
}
```

---

## 9. Güvenlik

### 9.1 Filter Chain Sırası

```
HTTP Request → TenantFilter → JwtAuthFilter → SecurityConfig filterChain
```

*WHY: `TenantFilter` ÖNCE çalışmalı — JWT doğrulaması sırasında `TenantContext` zaten set edilmiş olmalıdır (cross-tenant JWT doğrulaması için gerekli).*

**CSRF devre dışı** — *WHY: REST API JWT token-based auth kullanır. CSRF koruması session-based auth için gereklidir, stateless API'da gereksizdir.*

### 9.2 JWT Cross-Tenant Doğrulama

```kotlin
// JwtAuthenticationFilter içinde:
val contextTenantId = TenantContext.getTenantIdOrNull()
if (contextTenantId != null && jwtTenantId != null && contextTenantId != jwtTenantId) {
    response.sendError(403, "Cross-tenant erişim engellendi.")
    return
}
```

*WHY: Kullanıcı salon1.app.com'da salon2'nin JWT'sini kullanarak cross-tenant erişim deneyebilir.*

### 9.3 JWT Token Süresi

- Access token: 1h
- Refresh token (rol bazlı): PLATFORM_ADMIN=1d, TENANT_ADMIN=30d, CLIENT=60d, STAFF=7d

### 9.4 Token Rotation & Theft Detection

```kotlin
// RefreshToken entity — family bazlı theft detection
class RefreshToken(
    val id: String,           // JWT jti claim
    val userId: String,
    val tenantId: String,
    val family: String,       // Token ailesi — theft detection anahtarı
    val expiresAt: Instant,
    var isRevoked: Boolean = false
)
```

**Akış:** Login → yeni `family` UUID oluştur → refresh kullanıldığında eski revoke + yeni oluştur (aynı family) → **çalınan token tekrar kullanılırsa** → zaten revoked → aynı family'deki TÜM tokenlar revoke (tüm cihazlardan çıkış)

*WHY: Saldırgan çalınan refresh token'ı kullanırsa, meşru kullanıcı zaten yeni token almıştır. Eski token revoked olduğu için theft tespit edilir ve family bazlı tüm tokenlar iptal edilir.*

### 9.5 Brute Force Koruması

5 başarısız giriş → 15dk hesap kilidi (`lockedUntil` alanı). Başarılı girişte counter sıfırlanır.

### 9.6 Şifre Politikası

`BCryptPasswordEncoder(12)` — *WHY: 12 round güvenlik/performans dengesi.* `@Password` custom validator: min 8 karakter, büyük+küçük harf, rakam, yaygın şifre blacklist.

### 9.7 CORS Dinamik Origin

```kotlin
allowedOriginPatterns = mutableListOf(
    "https://*.app.com",      // Tenant subdomain'leri
    "http://localhost:3000"   // Geliştirme
)
// + tenant'ların kayıtlı custom domain'leri dinamik olarak eklenir
```

**WARNING:** Wildcard subdomain pattern'ı (`*.app.com`) preflight aşamasında `TenantFilter` çalışmadığı için kayıt dışı subdomain'ler de CORS geçer. Üretimde tam eşleşme (`allowedOrigins`) veya runtime doğrulaması tercih edilmelidir.

### 9.8 Rate Limiting (Endpoint-Bazlı)

| Endpoint | Limit | Periyot |
|----------|-------|---------|
| `/api/auth/login` | 5 | 1 dk |
| `/api/auth/register` | 5 | 1 dk |
| `/api/auth/forgot-password` | 3 | 1 dk |
| `/api/public/contact` | 3 | 1 dk |
| `/api/public/appointments` | 10 | 1 dk |
| `/api/admin/upload` | 20 | 1 dk |
| `/api/admin/**` (genel) | 100 | 1 dk |
| `/api/public/**` (genel) | 200 | 1 dk |

*Spesifik kurallar ÖNCE listelenmeli — `rules.firstOrNull { uri.startsWith(it.pathPrefix) }` ilk eşleşeni döndürür.*

### 9.9 Dosya Yükleme — 5 Katmanlı Güvenlik

```kotlin
fun upload(file: MultipartFile, tenantId: String): String {
    // 1. Size check (5MB) — WHY: Memory exhaustion koruması
    // 2. Extension whitelist (jpg, png, webp, gif) — WHY: Temel filtreleme
    // 3. Tika MIME detection — WHY: Uzantı sahte olabilir, content sniffing ile gerçek tip tespit
    // 4. ImageIO dimension check (4096x4096) — WHY: Decompression bomb koruması (küçük dosya, dev piksel)
    // 5. UUID filename + tenant-scoped path — WHY: Path traversal koruması + tenant izolasyonu
    val sanitizedFilename = UUID.randomUUID().toString() + "." + extension
    val path = "$tenantId/uploads/$sanitizedFilename"
    return storageProvider.upload(fileBytes, path)
}
```

---

## 10. Bildirim Sistemi

### @Async + NotificationContext Pattern

```kotlin
// Service'te:
val ctx = notificationService.toContext(appointment)  // Entity → DTO dönüşümü TRANSACTION İÇİNDE
notificationService.sendAppointmentConfirmation(ctx)  // @Async — farklı thread'de çalışır

// NotificationService'te:
@Async
@Retryable(maxAttempts = 3, backoff = Backoff(delay = 2000))
fun sendAppointmentConfirmation(ctx: NotificationContext) { ... }
```

*WHY: `@Async` metot farklı thread'de çalışır, Hibernate session kapalıdır. Entity proxy'nin lazy alanlarına erişim `LazyInitializationException` fırlatır. `toContext()` ile gerekli veriler transaction içinde DTO'ya kopyalanır.*

- **Template:** `{{variable}}` syntax + `HtmlUtils.htmlEscape()` — *WHY: XSS koruması, template injection önleme*
- **DeliveryStatus:** PENDING → SENT / FAILED / BOUNCED
- **@Retryable:** 3 deneme, 2s başlangıç delay — *WHY: Geçici network hataları için otomatik retry. Exception THROW edilmeli, swallow edilirse retry tetiklenmez!*

---

## 11. Ödeme & Abonelik

### iyzico Webhook Güvenliği

```kotlin
@PostMapping("/api/webhooks/iyzico")
fun handleIyzicoWebhook(
    @RequestBody payload: String,
    @RequestHeader("X-IYZ-SIGNATURE") signature: String?
): ResponseEntity<String> {
    // 1. HMAC-SHA256 imza doğrulama
    if (signature == null || !verifySignature(payload, signature)) {
        return ResponseEntity.status(401).body("Invalid signature")
    }
    // 2. Idempotency kontrolü
    val eventId = extractEventId(payload)
    if (processedWebhookEventRepository.existsByEventId(eventId)) {
        return ResponseEntity.ok("OK")  // Zaten işlendi — 200 dön
    }
    // 3. İşle + kaydet
    paymentService.processWebhookPayload(payload)
    processedWebhookEventRepository.save(ProcessedWebhookEvent(eventId = eventId, ...))
    return ResponseEntity.ok("OK")
}
```

*WHY (Idempotency): Webhook provider'lar aynı event'i birden fazla gönderebilir. `ProcessedWebhookEvent` tablosu (`event_id UNIQUE`) duplicate işlemeyi önler.*

### Subscription Lifecycle

```
TRIAL (14 gün, tüm modüller açık)
  ↓ süre doldu
EXPIRED  ←─── ödeme başarısız (3 retry/7 gün) ─── PAST_DUE
  ↑                                                    ↑
  └── iptal ── CANCELLED                    ödeme başarısız
                  ↑                                    ↑
                  └── iptal ── ACTIVE ── yenileme başarısız
                                 ↑
                                 └── ödeme başarılı (plan seçimi)
```

- **TRIAL:** 14 gün, tüm modüller açık (değerlendirme). Trial bitince → EXPIRED
- **ACTIVE → PAST_DUE:** Yenileme ödemesi başarısız → 7 gün grace period, 3 retry (her 2 günde)
- **PAST_DUE → EXPIRED:** 3 retry da başarısız → hesap kısıtlanır
- **pendingPlanChange:** Yükseltme bir sonraki billing cycle'da uygulanır

### Plan Limitleri

```kotlin
planLimitService.checkCanCreateAppointment(tenantId)  // Aylık randevu limiti
planLimitService.checkCanCreateStaff(tenantId)         // Personel limiti
moduleAccessService.requireAccess(tenantId, module)    // Modül erişimi
```

- TRIAL: tüm modüller açık, 1 personel, 100 randevu/ay
- STARTER: 1 personel, 100 randevu/ay, 500MB
- PROFESSIONAL: 5 personel, 500 randevu/ay, 2GB
- BUSINESS: 15 personel, 2000 randevu/ay, 5GB
- ENTERPRISE: sınırsız

### IndustryBundle

Sektör bazlı önerilen modül kombinasyonları. Tenant onboarding'de `BusinessType` seçimine göre default modüller aktifleştirilir.

---

## 12. Randevu Sistemi

### Status State Machine

```
PENDING ──→ CONFIRMED ──→ IN_PROGRESS ──→ COMPLETED
   │            │
   └──→ CANCELLED  ├──→ CANCELLED
                    └──→ NO_SHOW
```

Geçerli geçişler:
- `PENDING` → `CONFIRMED`, `CANCELLED`
- `CONFIRMED` → `IN_PROGRESS`, `CANCELLED`, `NO_SHOW`
- `IN_PROGRESS` → `COMPLETED`
- `COMPLETED`, `CANCELLED`, `NO_SHOW` → geçiş YAPILMAZ (son durum)
- Diğer tüm geçişler → `IllegalStateException`

### Conflict Detection

```kotlin
@Transactional(isolation = Isolation.READ_COMMITTED)
fun createAppointment(request: CreateAppointmentRequest): Appointment {
    val conflicts = appointmentRepository.findConflictingAppointments(...)  // PESSIMISTIC_WRITE
    if (conflicts.isNotEmpty()) throw AppointmentConflictException("Zaman diliminde çakışma var")
    // ...
}
```

*WHY: `READ_COMMITTED` + `FOR UPDATE` (PESSIMISTIC_WRITE) ile ikinci transaction birincinin bitmesini bekler. Race condition önlenir.*

### No-Show → Blacklist Akışı

1. Randevu `NO_SHOW` olarak işaretlenir (manuel veya scheduled job — confirmed + endTime + 1 saat geçmiş)
2. `user.noShowCount++`
3. `noShowCount >= 3` → `user.isBlacklisted = true`, `blacklistedAt` ve `blacklistReason` set edilir
4. Blacklisted müşteri yeni randevu oluşturamaz → `ClientBlacklistedException`
5. Kara liste bildirimi gönderilir

### Recurring Appointments

```kotlin
fun createRecurringAppointments(request, recurrenceRule: String, count: Int): List<Appointment> {
    val groupId = UUID.randomUUID().toString()
    for (i in 0 until count) {
        try {
            createAppointment(singleRequest).apply {
                this.recurringGroupId = groupId
                this.recurrenceRule = recurrenceRule  // "WEEKLY", "BIWEEKLY", "MONTHLY"
            }
        } catch (e: Exception) {
            logger.warn("Tekrarlayan randevu atlandı: $currentDate — ${e.message}")
            skippedDates.add(currentDate)  // Kısmi başarı — çakışan tarihler atlanır
        }
    }
}
```

*WHY: Per-appointment try-catch ile kısmi başarı desteklenir. Tek bir çakışma tüm seriyi iptal etmez.*

### Snapshot Pattern

Appointment'ta `clientName`, `clientEmail`, `clientPhone` saklanır. — *WHY: Müşteri bilgisi değişse bile randevu kaydındaki veri korunur.*

---

## 13. Background Jobs

- `@Scheduled` + `@SchedulerLock(name = "...", lockAtLeastFor = "4m", lockAtMostFor = "10m")` — *WHY: ShedLock multi-instance ortamda aynı job'ın paralel çalışmasını önler*
- `TenantAwareScheduler.executeForAllTenants { tenant -> ... }` — tenant iterasyonu + per-tenant TenantContext set/clear
- Tenant timezone: `ZoneId.of(settings?.timezone ?: "Europe/Istanbul")`
- **Per-item try-catch** — *WHY: Tek tenant'taki hata diğer tenant'ların işlenmesini engellememelidir*
- Logger: her hata `logger.error("[tenant={}] ...", tenant.slug, e.message, e)` ile loglanır

---

## 14. GDPR/KVKK

### Veri Taşınabilirlik Hakkı (Data Export)

```kotlin
gdprService.exportUserData(userId)  // → JSON dosyası (user, appointments, reviews)
```

### Unutulma Hakkı (Right to Erasure)

Silme yerine **anonimleştirme** — *WHY: Yasal kayıt tutma zorunluluğu, randevu/fatura kayıtları silinemez.*

```kotlin
// Appointment: clientName → "Anonim", clientEmail → "", clientPhone → ""
appointmentRepository.anonymizeByClientEmail(email)

// Review: comment → "[Silindi]"
reviewRepository.anonymizeByUserId(userId)

// RefreshToken: hard delete (kişisel veri, kayıt tutma zorunluluğu yok)
refreshTokenRepository.deleteByUserId(userId)

// Invoice: 10 YIL tutulmalı (Türk Ticaret Kanunu) — SİLME
```

### Consent Management

`ConsentRecord` entity — 5 consent type: `TERMS_OF_SERVICE`, `PRIVACY_POLICY`, `MARKETING_EMAIL`, `MARKETING_SMS`, `DATA_PROCESSING`. Her onay kaydı `grantedAt` + `ipAddress` ile saklanır.

---

## 15. Audit Logging

```kotlin
// AuditLog — TenantAwareEntity extend ETMEZ
@Entity
class AuditLog(
    val tenantId: String,
    val userId: String,
    val action: String,       // "CREATE_APPOINTMENT", "UPDATE_SERVICE"
    val entityType: String,   // "Appointment", "Service"
    val entityId: String,
    @Column(columnDefinition = "JSON")
    val details: String?,     // Değişen alanlar
    val ipAddress: String?
)
```

*WHY: `TenantAwareEntity` extend etmez çünkü PLATFORM_ADMIN tüm tenant'ların loglarını görebilmelidir. Hibernate filter cross-tenant görünürlüğü engeller. Manuel `tenantId` alanı + sorgu bazlı filtreleme kullanılır.*

---

## 16. Veritabanı & Migration

- **Collation:** `utf8mb4_turkish_ci` — *WHY: Türkçe karakter sıralaması (ğ, ş, ı, ö, ü, ç)*
- **Engine:** InnoDB
- **ID:** `VARCHAR(36) NOT NULL PRIMARY KEY` (UUID)
- **tenant_id:** `VARCHAR(36) NOT NULL` + `FOREIGN KEY REFERENCES tenants(id) ON DELETE CASCADE`
- **Timestamp:** `TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6)` — *WHY: Microsecond precision*
- **Boolean:** `BOOLEAN NOT NULL DEFAULT TRUE/FALSE`
- **Para:** `DECIMAL(10,2) NOT NULL DEFAULT 0.00`
- **Index adlandırma:** `idx_{tablo}_{sütunlar}` / `uk_{tablo}_{sütunlar}`
- **Migration:** `V{n}__{aciklama}.sql` → `src/main/resources/db/migration/`
- **sort_order:** `@ElementCollection` tabloları `sort_order INT DEFAULT 0` sütunu

---

## 17. Test Kuralları

- **Unit:** JUnit 5 + MockK — `@ExtendWith(MockKExtension::class)`, `@MockK`, `@InjectMockKs`, `every { }`, `verify { }`
- **Integration:** `@SpringBootTest` + `@Testcontainers` — MySQL 8.0 `--collation-server=utf8mb4_turkish_ci`
- **API:** `@WebMvcTest` + MockMvc + `@WithMockUser`
- **DB bağlantı:** `@DynamicPropertySource` ile container URL
- **Multi-tenant:** `TenantContext.setTenantId("test-tenant")` `@BeforeEach`, `TenantContext.clear()` `@AfterEach`
- **Concurrency:** `CountDownLatch` + `Thread` — pessimistic lock doğrulaması

---

## 18. Performans & DB Optimizasyonu (KRİTİK)

**TEMEL FELSEFE:** Veritabanı en kıymetli darboğaz noktasıdır. Her endpoint tasarımında "bu kaç query üretir?" sorusu sorulmalıdır. Redis, cache, batch işlemler ve akıllı query tasarımıyla DB yükü minimize edilmelidir.

### 18.1 Query Optimizasyonu

#### @Transactional(readOnly = true) zorunlu

Tüm okuma metotlarında (GET endpoint'leri, liste sorguları) kullanılmalıdır.

```kotlin
// DOĞRU:
@Transactional(readOnly = true)
fun listAppointments(tenantId: String, pageable: Pageable): PagedResponse<AppointmentResponse>

// YANLIŞ: readOnly olmadan okuma — gereksiz write lock alır
// @Transactional
// fun listAppointments(...)
```

*WHY: `readOnly = true` Hibernate'e dirty checking yapmamasını söyler, flush atlanır. MySQL'de read-only transaction MVCC snapshot'ı optimize eder. Performans farkı özellikle büyük result set'lerde belirgindir.*

#### N+1 Query Önleme

```kotlin
// YANLIŞ: Loop içinde DB query — 10 staff için 30+ query
fun findAvailableStaff(tenantId: String, date: LocalDate, ...): String? {
    val staffList = userRepository.findByTenantIdAndRole(tenantId, Role.STAFF)
    for (staff in staffList) {
        if (!isWithinWorkingHours(staff.id, date)) continue        // Query 1/staff
        if (isTimeSlotBlocked(staff.id, date)) continue            // Query 2/staff
        val conflicts = appointmentRepository.findConflicts(...)    // Query 3/staff
    }
}

// DOĞRU: Tek optimized query ile tüm kontrolleri yap
@Query("""
    SELECT DISTINCT u.id FROM User u
    JOIN WorkingHours wh ON wh.user = u AND wh.dayOfWeek = :dayOfWeek
    WHERE u.tenantId = :tenantId AND u.role IN ('STAFF', 'TENANT_ADMIN')
    AND NOT EXISTS (SELECT 1 FROM Appointment a WHERE a.staff = u AND a.date = :date
        AND a.status NOT IN ('CANCELLED') AND a.startTime < :endTime AND a.endTime > :startTime)
    AND NOT EXISTS (SELECT 1 FROM BlockedTimeSlot b WHERE b.staff = u AND b.date = :date
        AND b.startTime < :endTime AND b.endTime > :startTime)
""")
fun findAvailableStaffIds(...): List<String>
```

*WHY: N+1 problemi DB'yi exponential olarak yorar. 100 tenant × 10 staff × 3 query = 3000 query/request. Tek query ile 1 query/request'e düşer.*

#### JOIN FETCH — İlişkili entity gerektiğinde

```kotlin
// Liste view: Sadece gerekli alanlar (projection veya snapshot field)
@Transactional(readOnly = true)
fun listAppointments(pageable: Pageable): PagedResponse<AppointmentResponse>
// → Appointment'ta clientName, clientPhone zaten snapshot — JOIN gerekmez

// Detay view: İlişkili entity'ler gerekli → JOIN FETCH
@Query("""
    SELECT DISTINCT a FROM Appointment a
    LEFT JOIN FETCH a.staff
    LEFT JOIN FETCH a.services s
    LEFT JOIN FETCH s.service
    WHERE a.id = :id
""")
fun findByIdWithRelations(@Param("id") id: String): Appointment?
```

*WHY: Lazy loading detay sayfasında her ilişki için ayrı SELECT üretir. JOIN FETCH tek query'de tüm ilişkileri getirir.*

#### Interface Projection — Liste endpoint'lerinde

```kotlin
// DOĞRU: Sadece gerekli alanları çek
interface AppointmentSummary {
    fun getId(): String
    fun getDate(): LocalDate
    fun getClientName(): String
    fun getStatus(): AppointmentStatus
}

@Query("SELECT a.id as id, a.date as date, a.clientName as clientName, a.status as status FROM Appointment a WHERE a.tenantId = :tenantId")
fun findSummaries(@Param("tenantId") tenantId: String, pageable: Pageable): Page<AppointmentSummary>

// YANLIŞ: Tüm entity'yi çekip DTO'ya map etme (30 field çekip 5 field kullanma)
```

*WHY: Full entity fetch gereksiz veri transferi yapar. Projection sadece gerekli sütunları SELECT eder — memory ve network kullanımı düşer.*

#### Dashboard Aggregate Query — Tek query ile tüm metrikler

```kotlin
// YANLIŞ: Her metrik için ayrı COUNT query (5-6 query)
val total = appointmentRepository.countByTenantIdAndDate(tenantId, today)
val completed = appointmentRepository.countByTenantIdAndStatusAndDate(tenantId, COMPLETED, today)
val pending = appointmentRepository.countByTenantIdAndStatusAndDate(tenantId, PENDING, today)
val revenue = paymentRepository.sumByTenantIdAndDate(tenantId, today)

// DOĞRU: Tek query ile CASE WHEN
@Query("""
    SELECT new com.aesthetic.backend.dto.response.DashboardStats(
        COUNT(a),
        SUM(CASE WHEN a.status = 'COMPLETED' THEN 1 ELSE 0 END),
        SUM(CASE WHEN a.status = 'PENDING' THEN 1 ELSE 0 END),
        SUM(CASE WHEN a.status = 'CANCELLED' THEN 1 ELSE 0 END),
        COALESCE(SUM(CASE WHEN a.status = 'COMPLETED' THEN a.totalPrice ELSE 0 END), 0)
    )
    FROM Appointment a WHERE a.tenantId = :tenantId AND a.date = :date
""")
fun getDashboardStats(@Param("tenantId") tenantId: String, @Param("date") date: LocalDate): DashboardStats
```

*WHY: 6 ayrı COUNT query yerine 1 aggregate query. Dashboard en sık çağrılan endpoint'tir — her optimize edilmemiş query DB'yi orantısız yorar.*

### 18.2 Cache Stratejisi

#### Cache TTL Farklılaştırma

Her veri tipi için farklı TTL kullan — *WHY: Tüm cache'leri aynı TTL ile yönetmek ya stale data ya da gereksiz invalidation üretir.*

| Veri Tipi | TTL | Gerekçe |
|-----------|-----|---------|
| SiteSettings (timezone, theme) | 15 dk | Nadir değişir |
| Services / Products | 5 dk | Orta volatilite |
| Dashboard stats | 1-2 dk | Sık değişir ama her request'te hesaplanması gereksiz |
| Blog / Gallery | 10 dk | İçerik nadiren güncellenir |
| Availability slots | **CACHE YOK** | Gerçek zamanlı doğruluk şart (çift randevu riski) |
| Tenant entity (çözümleme) | 5 dk | Caffeine local, deaktivasyon toleransı |

```kotlin
// Dashboard cache örneği — 1 dk TTL
@Cacheable(value = ["dashboard-stats"], keyGenerator = "tenantCacheKeyGenerator")
@Transactional(readOnly = true)
fun getTodayStats(tenantId: String, date: LocalDate): DashboardStats

// Randevu oluşturulunca dashboard cache invalidate
@CacheEvict(value = ["dashboard-stats"], keyGenerator = "tenantCacheKeyGenerator")
@Transactional
fun createAppointment(request: CreateAppointmentRequest): AppointmentResponse
```

#### Redis vs Caffeine

- **Caffeine (local):** Tek instance geliştirme ortamı, tenant çözümleme cache'i
- **Redis (distributed):** Production multi-instance ortam, entity cache, rate limiting, session
- *WHY: Caffeine multi-instance'da senkronize olmaz — Instance A cache invalidate eder, Instance B 5 dk stale data sunar. Production'da Redis zorunlu.*

#### Sık Okunan Ama Nadir Değişen Verilerin Cache Stratejisi

```kotlin
// SiteSettings — her request'te okunur (timezone, theme, logo)
// Cache'lemeden: Her request 1 SELECT query = dakikada 200 query
// Cache ile: 15 dk'da 1 SELECT query
@Cacheable(value = ["site-settings"], keyGenerator = "tenantCacheKeyGenerator")
@Transactional(readOnly = true)
fun getSettings(tenantId: String): SiteSettings?

// Güncelleme'de cache evict
@CacheEvict(value = ["site-settings"], keyGenerator = "tenantCacheKeyGenerator")
@Transactional
fun updateSettings(tenantId: String, request: UpdateSettingsRequest): SiteSettingsResponse
```

### 18.3 Batch İşlemler & JDBC Optimizasyonu

#### JDBC Batch Configuration (Zorunlu)

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50
        order_inserts: true
        order_updates: true
```

*WHY: Bu ayar olmadan `saveAll(50 entity)` = 50 ayrı INSERT statement. Batch ile tek roundtrip'te 50 INSERT gönderilir. Recurring appointment (52 hafta) ve bulk notification log gibi işlemlerde kritik fark yaratır.*

#### Büyük Batch İşlemleri Chunk'la

```kotlin
// YANLIŞ: 1000 entity'yi tek seferde saveAll
repository.saveAll(largeList)  // OutOfMemoryError riski + uzun transaction

// DOĞRU: Chunk'lara böl
largeList.chunked(50).forEach { chunk ->
    repository.saveAll(chunk)
    entityManager.flush()
    entityManager.clear()  // Persistence context temizle — memory leak önle
}
```

### 18.4 Scheduled Job Optimizasyonu

#### Paralel Tenant İşleme

```kotlin
// MEVCUT (Seri): 100 tenant × 2s = 200s — job bitiremeden yeni cycle başlar
fun executeForAllTenants(action: (Tenant) -> Unit) {
    for (tenant in tenants) { action(tenant) }  // Seri
}

// HEDEFLENMELİ (Paralel): 100 tenant × 2s / 5 thread = 40s
fun executeForAllTenantsParallel(action: (Tenant) -> Unit) {
    val futures = tenants.map { tenant ->
        CompletableFuture.runAsync({
            try {
                TenantContext.setTenantId(tenant.id!!)
                action(tenant)
            } finally {
                TenantContext.clear()
            }
        }, taskExecutor)
    }
    CompletableFuture.allOf(*futures.toTypedArray()).join()
}
```

*WHY: Seri işleme 100+ tenant'ta darboğaz yaratır. 5 dk'lık reminder job 200s sürerse, 5 dk'da bir çalışan schedule ile üst üste biner. Paralel işleme bu riski ortadan kaldırır.*

### 18.5 Connection Pool Yönetimi

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50         # Prod — tenant sayısı × ortalama concurrent request
      minimum-idle: 10
      connection-timeout: 30000     # 30s — peak load'da 20s yetersiz kalabilir
      leak-detection-threshold: 60000  # 60s — her zaman aktif
      max-lifetime: 1800000         # 30 dk — MySQL wait_timeout'tan kısa olmalı
```

*WHY: Connection pool tükenirse yeni request'ler timeout alır. Scheduled job'lar connection tutar — seri 100 tenant işleme sırasında diğer request'ler aç kalır. Pool size = max concurrent DB operations.*

### 18.6 Index Stratejisi

- **Composite index'te leftmost prefix:** En sık filtrelenen sütun solda — `(tenant_id, date, status)` `tenant_id` tek başına da kullanılabilir
- **Negative condition index bypass:** `status NOT IN (...)` index kullanmayabilir — pozitif condition tercih et veya index'i sadeleştir
- **Covering index:** Sık tekrarlanan SELECT'ler için tüm sütunları index'e dahil et

```sql
-- YANLIŞ: 6 sütunlu index — negative condition bypass edebilir
INDEX idx_appt_conflict (tenant_id, staff_id, date, start_time, end_time, status)

-- DOĞRU: Temel sütunlar + status filtresi application layer'da
INDEX idx_appt_conflict (tenant_id, staff_id, date)
```

### 18.7 Endpoint Tasarım Prensipleri

- **Bir endpoint = minimum DB query:** Her endpoint için "kaç query üretir?" sorusu sor
- **Aggregate endpoint:** Dashboard, istatistik, özet sayfaları tek query ile CASE WHEN + GROUP BY
- **Lazy field'ları zorunlu olmadıkça yükleme:** Liste endpoint'inde sadece gerekli alanlar (projection)
- **Pagination zorunlu:** Liste döndüren TÜM endpoint'ler `Pageable` kabul etmeli — unbounded SELECT yapma
- **Availability endpoint'inde limit:** `staffId = null` ise tüm personel hesaplanır — ilk 20 uygun slot ile sınırla
- **Bulk endpoint tasarla:** Tekil CRUD yanında `POST /api/admin/appointments/bulk` gibi batch endpoint'ler

### 18.8 Event-Driven Mimari (Gelecek Roadmap)

Mevcut ölçekte `@Async` yeterlidir. 50+ tenant'a ulaşıldığında:

```
@Async (mevcut) → Kafka/RabbitMQ (gelecek)

Hedef event'ler:
- AppointmentCreatedEvent → NotificationConsumer (bildirim)
- AuditEvent → AuditLogConsumer (audit log yazma — ana transaction'ı yavaşlatmaz)
- WebhookReceivedEvent → PaymentConsumer (async webhook işleme)
```

*WHY: Senkron bildirim gönderimi ana request'i yavaşlatır. Audit log yazma her write operation'a 1 INSERT ekler. Event-driven mimari bu yan etkileri ana akıştan ayırır.*

---

## 19. Kod Kalitesi Prensipleri

### 19.1 DRY (Don't Repeat Yourself)

Aynı kod bloğu 2 kez yazılacaksa → fonksiyon veya utility yap.

```kotlin
// YANLIŞ: Her scheduled job'da timezone hesaplama tekrarı
// AppointmentReminderJob:
val settings = siteSettingsRepository.findByTenantId(tenant.id!!)
val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")
val now = ZonedDateTime.now(tenantZone)

// TrialExpirationJob: (aynı 3 satır tekrar)
// NoShowDetectionJob: (aynı 3 satır tekrar)

// DOĞRU: Utility function
fun getTenantZonedNow(tenantId: String): ZonedDateTime {
    val settings = siteSettingsRepository.findByTenantId(tenantId)
    val zone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")
    return ZonedDateTime.now(zone)
}
```

Benzer şekilde:
- **Appointment validation** (working hours + blocked slots + conflict check) → composite validation service'e çıkar
- **Notification context oluşturma** → `toContext()` pattern zaten DRY (doğru)
- **Error response oluşturma** → `GlobalExceptionHandler` zaten DRY (doğru)

### 19.2 SOLID Prensipleri

- **S (Single Responsibility):** Her service tek bir aggregate root'u yönetir. `AppointmentService` randevu, `PaymentService` ödeme. Bir service'te 500+ satır varsa → bölünmeli
- **O (Open/Closed):** Yeni `NotificationType` eklerken mevcut kodu değiştirme → strategy pattern veya template tabanlı
- **L (Liskov):** `TenantAwareEntity` extend eden tüm entity'ler aynı tenant izolasyon davranışını göstermeli
- **I (Interface Segregation):** Repository interface'leri küçük ve odaklı — bir repository'de 30+ metot varsa bölünmeli
- **D (Dependency Inversion):** Service'ler birbirine concrete class değil interface üzerinden bağımlı olmalı

### 19.3 Cyclomatic Complexity

Bir fonksiyonun complexity'si **maksimum 10** olmalı. 10'u geçerse parçala.

```kotlin
// YANLIŞ: Tek fonksiyonda çok fazla branching
fun processAppointment(request: Request): Response {
    if (condition1) {
        if (condition2) {
            if (condition3) { ... }
        }
    }
}

// DOĞRU: Early return + helper function'lar
fun processAppointment(request: Request): Response {
    validateRequest(request)           // İlk validation aşaması
    val staff = resolveStaff(request)  // Staff çözümleme
    val slot = validateSlot(request, staff)  // Slot doğrulama
    return createAndSave(request, staff, slot) // Kayıt
}
```

### 19.4 Gereksiz Kod Yazma (YAGNI)

- Henüz gerek olmayan feature için altyapı KURMA
- "İleride lazım olur" diye abstract layer EKLEME
- Kullanılmayan metot/endpoint BIRAKMA
- Tek yerde kullanılan logic için utility class OLUŞTURMA

### 19.5 Deprecated API Kullanma

- Spring Boot 3.4+ ve Hibernate 6'nın güncel API'lerini kullan
- `@Deprecated` annotated metotları kullanma — modern karşılığını bul
- Jakarta namespace kullan (`javax.*` değil `jakarta.*`)

---

## 20. Build & Run

```bash
./gradlew build && ./gradlew test && ./gradlew bootJar
docker compose up -d
```

---

## 21. Anti-Pattern'ler (YAPMA)

Her madde: ❌ Yanlış → ✅ Doğru — *WHY: gerekçe*

### Mimari & Multi-Tenant

1. ❌ `LocalDateTime` → ✅ `Instant` (UTC) — *WHY: Server timezone'una bağımlılık, farklı sunucularda farklı sonuç*
2. ❌ `Double` fiyat → ✅ `BigDecimal` — *WHY: `0.1 + 0.2 ≠ 0.3` floating point hatası*
3. ❌ `hasRole()` → ✅ `hasAuthority()` — *WHY: `hasRole()` otomatik `ROLE_` prefix ekler, DB'de `TENANT_ADMIN` saklanıyor*
4. ❌ `FetchType.EAGER` → ✅ `FetchType.LAZY` — *WHY: N+1 query problemi, gereksiz JOIN'ler*
5. ❌ Entity döndürme → ✅ Response DTO mapping — *WHY: Hassas alan sızıntısı (passwordHash), döngüsel referans, lazy proxy hatası*
6. ❌ `InheritableThreadLocal` → ✅ `ThreadLocal` + `TenantAwareTaskDecorator` — *WHY: Thread pool'da eski parent thread bilgisi kalır, veri sızıntısı*
7. ❌ `@CacheEvict(allEntries=true)` → ✅ Tenant-scoped eviction — *WHY: Tüm tenant'ların cache'i silinir*
8. ❌ `SERIALIZABLE` isolation → ✅ `READ_COMMITTED` + `PESSIMISTIC_WRITE` — *WHY: MySQL InnoDB'de deadlock riski*
9. ❌ `TenantAwareEntity` extend unutma → ✅ Tüm tenant-scoped entity'ler extend etmeli — *WHY: Filter ve EntityListener miras alınmaz, veri izolasyonu kırılır*
10. ❌ `tenant_id` manuel set → ✅ `TenantEntityListener` otomatik set eder — *WHY: Çift set cross-tenant yazma riski, listener zaten doğrular*
11. ❌ `@Async`'te Entity kullanma → ✅ `NotificationContext` DTO — *WHY: `LazyInitializationException` (Hibernate session kapalı)*
12. ❌ `@Retryable`'da exception swallow → ✅ Exception throw et — *WHY: Retry mekanizması exception'a bağlıdır, swallow edilirse sessiz hata*
13. ❌ Server default timezone → ✅ `ZoneId.of(settings?.timezone ?: "Europe/Istanbul")` — *WHY: Her tenant farklı timezone'da*
14. ❌ `findById()` sadece FK set etmek için → ✅ `entityManager.getReference()` — *WHY: Gereksiz SELECT sorgusu, proxy yeterli*
15. ❌ String literal JPQL enum → ✅ `@Param` ile parametre geç — *WHY: Tip güvenliği yok, refactoring'de kırılır*
16. ❌ `@Transactional` self-invocation → ✅ Farklı bean'den çağır — *WHY: Spring proxy bypass, transaction yönetilmez*

### Performans & DB

17. ❌ Loop içinde repository query → ✅ Tek query ile batch fetch / JOIN — *WHY: N+1 problemi, DB'yi exponential yorar*
18. ❌ Dashboard'da çoklu COUNT query → ✅ Tek CASE WHEN aggregate query — *WHY: 6 query yerine 1 query*
19. ❌ Liste endpoint'inde full entity fetch → ✅ Interface projection veya snapshot field — *WHY: 30 field çekip 5 field kullanmak gereksiz I/O*
20. ❌ Okuma metotlarında `@Transactional` (readOnly yok) → ✅ `@Transactional(readOnly = true)` — *WHY: Dirty checking skip, flush atlanır, MVCC optimizasyonu*
21. ❌ `saveAll()` batch config olmadan → ✅ `jdbc.batch_size: 50` + `order_inserts: true` — *WHY: Config olmadan N ayrı INSERT, config ile tek batch roundtrip*
22. ❌ Unbounded SELECT (pagination yok) → ✅ Tüm liste endpoint'lerinde `Pageable` zorunlu — *WHY: 10.000 kayıt döndürme memory ve network'ü çökertir*
23. ❌ Sık okunan veriyi her seferinde DB'den çekme → ✅ Redis/Caffeine cache + uygun TTL — *WHY: SiteSettings her request'te okunur — cache olmadan dakikada 200 gereksiz query*
24. ❌ Scheduled job'larda seri tenant işleme (100+ tenant) → ✅ Paralel işleme (CompletableFuture) — *WHY: 100 tenant × 2s = 200s seri, 40s paralel*

### Kod Kalitesi

25. ❌ Aynı kod bloğu 2+ yerde tekrar → ✅ Utility function / service'e çıkar (DRY) — *WHY: Bir yerde düzeltme diğerlerini kaçırır, bakım maliyeti artar*
26. ❌ Tek fonksiyonda 10+ branching (cyclomatic complexity) → ✅ Early return + helper function'lara böl — *WHY: Okunabilirlik ve test edilebilirlik düşer*
27. ❌ "İleride lazım olur" diye altyapı kurma → ✅ YAGNI — sadece şu an gereken kodu yaz — *WHY: Kullanılmayan abstraction bakım maliyeti üretir*
28. ❌ Deprecated API kullanma → ✅ Modern karşılığını bul (`javax.*` → `jakarta.*`) — *WHY: Gelecek versiyonlarda kaldırılır, güvenlik yaması almaz*
