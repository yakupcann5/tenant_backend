# Kotlin + Spring Boot — Multi-Tenant SaaS Backend Mimarisi

## 1. Genel Bakış

Bu doküman, mevcut Next.js frontend'i destekleyecek **Kotlin + Spring Boot** tabanlı multi-tenant SaaS backend mimarisini tanımlar. Platform; güzellik klinikleri, diş klinikleri, berber dükkanları ve kuaför salonları için randevu yönetim sistemi sunar.

### Tech Stack

| Katman | Teknoloji |
|--------|-----------|
| Dil | Kotlin 2.0+ |
| Framework | Spring Boot 3.4+ |
| Veritabanı | MySQL 8.0+ |
| ORM | Spring Data JPA + Hibernate 6 |
| Güvenlik | Spring Security 6 + JWT (jjwt) |
| Validation | Jakarta Validation (Bean Validation 3.0) |
| Migration | Flyway |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Cache | Redis (opsiyonel, session + cache) |
| Build | Gradle (Kotlin DSL) |
| Test | JUnit 5 + Testcontainers + MockK |
| Containerization | Docker + Docker Compose |

---

## 2. Multi-Tenant Strateji

### 2.1 Yaklaşım: Shared Database, Shared Schema (Discriminator Column)

Tüm tenant'lar **aynı veritabanı ve aynı tabloları** paylaşır. Her tablo `tenant_id` sütununa sahiptir. Bu yaklaşım:

- **Avantaj:** Düşük maliyet, kolay yönetim, hızlı tenant oluşturma
- **Avantaj:** Tek migration ile tüm tenant'lar güncellenir
- **Dezavantaj:** Veri izolasyonu uygulama seviyesinde (Hibernate Filter ile)
- **Uygunluk:** Orta ölçekli SaaS (10.000+ tenant'a kadar)

### 2.2 Tenant Çözümleme (Resolution)

```
İstek akışı:
  salon1.app.com → DNS → Load Balancer → Spring Boot
                                           │
                                     TenantFilter
                                           │
                              ┌────────────┴────────────┐
                              │                         │
                        Authenticated?              Anonymous?
                              │                         │
                     JWT tenantId claim          Subdomain'den çöz
                              │                         │
                              └────────┬────────────────┘
                                       │
                                TenantContext (ThreadLocal)
                                       │
                                Hibernate Filter aktif
                                       │
                                Tüm sorgular tenant_id ile filtrelenir
```

**Çözümleme sırası (güvenlik öncelikli):**
1. **JWT claim (öncelikli):** Authenticated isteklerde token içindeki `tenantId` kullanılır. Subdomain ile JWT uyuşmazlığı → 403 hatası.
2. **Subdomain:** `{tenant-slug}.app.com` — anonymous istekler için (public API).
3. **Platform admin:** `/api/platform/**` istekleri tenant gerektirmez.

> **GÜVENLİK NOTU:** `X-Tenant-ID` header'ı **KALDIRILDI**. Önceki versiyonda herhangi biri header göndererek başka tenant'ın verisine erişebiliyordu. Artık:
> - Anonymous istekler → subdomain'den çözümlenir (sahteleme mümkün değil, DNS seviyesinde korunur)
> - Authenticated istekler → JWT'deki `tenantId` claim kullanılır (sunucu tarafında imzalı, sahteleme mümkün değil)
> - Subdomain ≠ JWT tenantId → istek reddedilir (cross-tenant saldırı engellenir)

### 2.3 Implementasyon

```kotlin
// TenantContext.kt — ThreadLocal ile tenant bilgisi taşıma
object TenantContext {
    // DİKKAT: InheritableThreadLocal DEĞİL, düz ThreadLocal kullanılmalı!
    // InheritableThreadLocal thread pool'larda sorunludur: thread yeniden kullanıldığında
    // eski parent thread'in tenant bilgisi kalır ve yanlış tenant'a erişim riski oluşur.
    // Async propagation için TenantAwareTaskDecorator kullanılır (bkz. 2.4).
    private val currentTenant = ThreadLocal<String>()

    fun setTenantId(tenantId: String) = currentTenant.set(tenantId)
    fun getTenantId(): String = currentTenant.get()
        ?: throw TenantNotFoundException("Tenant context bulunamadı")
    fun getTenantIdOrNull(): String? = currentTenant.get()
    fun clear() = currentTenant.remove()
}
```

```kotlin
// TenantFilter.kt — Her istekte tenant çözümleme (GÜVENLİ VERSİYON)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TenantFilter(
    private val tenantRepository: TenantRepository
) : OncePerRequestFilter() {

    companion object {
        private val logger = LoggerFactory.getLogger(TenantFilter::class.java)
    }

    // Caffeine local cache — Her istekte DB sorgusu yapmayı önler.
    // TTL 5 dakika: Tenant deaktive edilirse max 5 dk gecikmeyle algılanır.
    // Tenant deaktivasyonunda manuel invalidation için invalidateTenantCache() metodu var.
    private val tenantCache: Cache<String, Tenant> = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build()

    // Platform admin ve auth endpoint'leri tenant gerektirmez
    // exactPaths: Tam eşleşme (prefix değil!)
    // prefixPaths: Prefix eşleşme (alt yollar dahil)
    private val exactExemptPaths = setOf(
        "/api/auth/login",
        "/api/auth/register",
        "/api/auth/refresh",
        "/api/auth/forgot-password",
        "/api/auth/reset-password"
    )
    private val prefixExemptPaths = listOf(
        "/api/platform/",
        "/api/webhooks/",           // Dış servis callback (iyzico vb.) — tenant gerektirmez
        "/swagger-ui/",
        "/v3/api-docs",
        "/actuator/"
    )

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        return uri in exactExemptPaths || prefixExemptPaths.any { uri.startsWith(it) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            val host = request.serverName
            val tenant = resolveTenant(host)

            TenantContext.setTenantId(tenant.id!!)
            filterChain.doFilter(request, response)
        } finally {
            TenantContext.clear()
        }
    }

    /**
     * Tenant çözümleme: Önce subdomain, başarısız olursa custom domain dener.
     * Sonuç Caffeine cache'te tutulur (5 dk TTL).
     */
    private fun resolveTenant(host: String): Tenant {
        // 1. Subdomain dene: salon1.app.com → slug = "salon1"
        val slug = extractSubdomain(host)
        if (slug != null) {
            return tenantCache.get(slug) { key ->
                tenantRepository.findBySlugAndIsActiveTrue(key)
                    ?: throw TenantNotFoundException("Tenant bulunamadı veya aktif değil: $key")
            }
        }

        // 2. Custom domain dene: salongüzellik.com → customDomain alanından çöz
        // NOT: Bu özellik Tenant entity'sinde customDomain alanı gerektirir.
        val cacheKey = "domain:$host"
        return tenantCache.get(cacheKey) { _ ->
            tenantRepository.findByCustomDomainAndIsActiveTrue(host)
                ?: throw TenantNotFoundException(
                    "Tenant belirlenemedi. Subdomain ({slug}.app.com) veya kayıtlı custom domain gerekli."
                )
        }
    }

    /**
     * Subdomain çıkarma: salon1.app.com → "salon1"
     * www, api, admin subdomain'leri hariç tutulur.
     */
    private fun extractSubdomain(host: String): String? {
        val parts = host.split(".")
        if (parts.size >= 3) {
            val subdomain = parts.first()
            if (subdomain !in setOf("www", "api", "admin")) {
                return subdomain
            }
        }
        return null
    }

    /**
     * Tenant deaktive edildiğinde cache'ten manual invalidation.
     * Admin API'den çağrılmalı.
     */
    fun invalidateTenantCache(slug: String) {
        tenantCache.invalidate(slug)
        logger.info("Tenant cache invalidated: $slug")
    }
}
```

```kotlin
// JwtAuthenticationFilter.kt — JWT tenantId doğrulaması (cross-tenant saldırı engelleme)
@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val token = extractToken(request)
        if (token != null && jwtTokenProvider.validateToken(token)) {
            val claims = jwtTokenProvider.getClaims(token)
            val jwtTenantId = claims["tenantId"] as? String

            // KRİTİK: JWT'deki tenantId ile TenantFilter'dan gelen tenantId eşleşmeli
            val contextTenantId = TenantContext.getTenantIdOrNull()
            if (contextTenantId != null && jwtTenantId != null && contextTenantId != jwtTenantId) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Token ile subdomain tenant uyuşmuyor. Cross-tenant erişim engellendi.")
                return
            }

            // Authentication context'e kullanıcı bilgisi ekle
            val authentication = jwtTokenProvider.getAuthentication(token)
            SecurityContextHolder.getContext().authentication = authentication
        }

        filterChain.doFilter(request, response)
    }

    private fun extractToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization")
        return if (header?.startsWith("Bearer ") == true) header.substring(7) else null
    }
}
```

```kotlin
// TenantAwareEntity.kt — Tüm tenant-scoped entity'lerin base class'ı
// @FilterDef burada tanımlanır, tüm child entity'ler otomatik miras alır.
// @EntityListeners ile INSERT/UPDATE/DELETE koruması sağlanır.
@FilterDef(
    name = "tenantFilter",
    parameters = [ParamDef(name = "tenantId", type = "string")]
)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@EntityListeners(TenantEntityListener::class)
@MappedSuperclass
abstract class TenantAwareEntity {
    @Column(name = "tenant_id", nullable = false, updatable = false)
    lateinit var tenantId: String
}
```

> **ÖNEMLİ:** Tüm tenant-scoped entity'ler `TenantAwareEntity`'den extend etmelidir. Bu sayede:
> - `@FilterDef` ve `@Filter` annotation'ları otomatik miras alınır (SELECT koruması)
> - `@EntityListeners(TenantEntityListener)` otomatik miras alınır (INSERT/UPDATE/DELETE koruması)
> - Entity'lerde tekrar tanımlama gerekmez.
>
> **Hibernate 6 NOTU:** Bazı Hibernate 6 versiyonlarında `@FilterDef`'in `@MappedSuperclass` üzerinden miras alınmasında sorun olabilir. Bu durumda `@FilterDef` + `@Filter` annotation'larını her entity üzerinde ayrı ayrı tanımlayın.

```kotlin
// TenantAspect.kt — EntityManager'da filter aktifleştirme (SELECT koruması)
@Aspect
@Component
class TenantAspect(private val entityManager: EntityManager) {

    // KRİTİK: TenantRepository HARİÇ tutulmalı! Çünkü TenantFilter içinde
    // TenantContext set edilmeden ÖNCE TenantRepository çağrılır.
    // Ayrıca RefreshTokenRepository de hariç (token rotation'da tenant context olmayabilir).
    @Before(
        "execution(* com.aesthetic.backend.repository..*.*(..)) " +
        "&& !execution(* com.aesthetic.backend.repository.TenantRepository.*(..)) " +
        "&& !execution(* com.aesthetic.backend.repository.RefreshTokenRepository.*(..))"
    )
    fun enableTenantFilter() {
        val tenantId = TenantContext.getTenantIdOrNull() ?: return  // Platform admin bypass
        val session = entityManager.unwrap(Session::class.java)
        session.enableFilter("tenantFilter")
            .setParameter("tenantId", tenantId)
    }
}
```

```kotlin
// TenantEntityListener.kt — INSERT/UPDATE/DELETE koruması (JPA EntityListener)
// Hibernate @Filter sadece SELECT'leri korur! INSERT/UPDATE/DELETE için bu listener gerekli.
//
// NOT: JPA EntityListener'lar Spring-managed bean DEĞİLDİR. TenantContext'e erişim için
// static/object erişim kullanılır (TenantContext zaten object singleton).
class TenantEntityListener {

    @PrePersist
    fun onPrePersist(entity: Any) {
        if (entity is TenantAwareEntity) {
            val tenantId = TenantContext.getTenantId()
            if (!entity::tenantId.isInitialized) {
                entity.tenantId = tenantId
            } else if (entity.tenantId != tenantId) {
                throw SecurityException(
                    "Başka tenant'a veri yazma girişimi engellendi! " +
                    "Beklenen: $tenantId, Gelen: ${entity.tenantId}"
                )
            }
        }
    }

    @PreUpdate
    fun onPreUpdate(entity: Any) {
        if (entity is TenantAwareEntity && entity.tenantId != TenantContext.getTenantId()) {
            throw SecurityException(
                "Başka tenant'ın verisini güncelleme girişimi engellendi!"
            )
        }
    }

    @PreRemove
    fun onPreRemove(entity: Any) {
        if (entity is TenantAwareEntity && entity.tenantId != TenantContext.getTenantId()) {
            throw SecurityException(
                "Başka tenant'ın verisini silme girişimi engellendi!"
            )
        }
    }
}
```

### 2.4 Async İşlemlerde Tenant Context Propagation

> **KRİTİK SORUN:** `ThreadLocal` (ve `InheritableThreadLocal`) `@Async` metotlarda, `CompletableFuture`, ve thread pool executor'larda propagate OLMAZ. Bildirim, e-posta, SMS gibi async işlemler tenant bilgisini kaybeder.

```kotlin
// TenantAwareTaskDecorator.kt — Async thread'lere tenant context taşıma
class TenantAwareTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        // Ana thread'deki tenant bilgisini yakala
        val tenantId = TenantContext.getTenantIdOrNull()

        // KRİTİK: SecurityContext KOPYALANMALI, referans paylaşılmamalı!
        // Aksi halde parent thread context'i değiştirirse child thread etkilenir.
        val originalContext = SecurityContextHolder.getContext()
        val clonedContext = SecurityContextHolder.createEmptyContext().apply {
            authentication = originalContext.authentication
        }

        return Runnable {
            try {
                // Yeni thread'e tenant bilgisini aktar
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

```kotlin
// AsyncConfig.kt — Async executor konfigürasyonu
@Configuration
@EnableAsync
@EnableCaching                 // Caffeine local cache aktif
@EnableScheduling              // @Scheduled job'lar aktif (Bölüm 18-19)
@EnableRetry                   // @Retryable (bildirim gönderimi vb.)
class AsyncConfig {
    @Bean("taskExecutor")
    fun taskExecutor(): TaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 5
        executor.maxPoolSize = 20
        executor.queueCapacity = 100
        executor.setThreadNamePrefix("async-tenant-")
        executor.setTaskDecorator(TenantAwareTaskDecorator())  // KRİTİK!
        // NOT: executor.initialize() çağrısı GEREKMEZ.
        // Spring @Bean lifecycle otomatik olarak afterPropertiesSet() → initialize() çağırır.
        return executor
    }
}
```

### 2.5 Scheduled Tasks'ta Tenant Context

Zamanlanan görevler (hatırlatıcılar, temizlik, trial süresi kontrolü) tüm tenant'ları iterate etmelidir:

```kotlin
// TenantAwareScheduler.kt — Scheduled task'lar için tenant context yönetimi
@Component
class TenantAwareScheduler(
    private val tenantRepository: TenantRepository
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TenantAwareScheduler::class.java)
    }
    /**
     * Tüm aktif tenant'lar üzerinde bir işlem çalıştır.
     * Her tenant için TenantContext set edilir ve sonra temizlenir.
     */
    fun executeForAllTenants(action: (Tenant) -> Unit) {
        val tenants = tenantRepository.findAllByIsActiveTrue()
        for (tenant in tenants) {
            try {
                TenantContext.setTenantId(tenant.id!!)
                action(tenant)
            } catch (e: Exception) {
                logger.error("[tenant={}] Scheduled task hatası: {}", tenant.slug, e.message, e)
            } finally {
                TenantContext.clear()
            }
        }
    }
}

// Kullanım örneği (tam implementasyon: Bkz. §18.3 AppointmentReminderJob):
// tenantAwareScheduler.executeForAllTenants { tenant ->
//     // Tenant context otomatik set edilir, iş mantığını çalıştır
//     notificationService.sendUpcomingAppointmentReminders()
// }
```

### 2.6 Tenant-Aware Cache Stratejisi

Redis cache key'leri tenant bazlı olmalıdır:

```kotlin
// TenantAwareCacheKeyGenerator.kt
@Component("tenantCacheKeyGenerator")
class TenantAwareCacheKeyGenerator : KeyGenerator {
    override fun generate(target: Any, method: Method, vararg params: Any?): Any {
        val tenantId = TenantContext.getTenantId()
        val key = params.joinToString(":")
        return "tenant:$tenantId:${method.name}:$key"
    }
}

// Kullanım:
@Cacheable(value = ["services"], keyGenerator = "tenantCacheKeyGenerator")
fun getActiveServices(): List<Service> { ... }

// DİKKAT: allEntries=true KULLANMAYIN! Tüm tenant'ların cache'ini siler.
// Bunun yerine tenant-scoped eviction yapın:
@CacheEvict(value = ["services"], keyGenerator = "tenantCacheKeyGenerator")
fun createService(request: CreateServiceRequest): Service { ... }

// Eğer bir tenant'ın TÜM cache entry'lerini silmek gerekiyorsa:
// Redis'te pattern-based silme kullanın:
@Component
class TenantCacheManager(private val redisTemplate: StringRedisTemplate) {
    /**
     * Belirli bir tenant'ın belirli bir cache grubundaki TÜM entry'lerini temizler.
     * Pattern: "tenant:{tenantId}:*"
     * allEntries=true yerine bu metodu kullanın!
     */
    fun evictAllForCurrentTenant(cacheName: String) {
        val tenantId = TenantContext.getTenantId()
        val pattern = "tenant:$tenantId:$cacheName:*"
        val keys = redisTemplate.keys(pattern)
        if (keys.isNotEmpty()) {
            redisTemplate.delete(keys)
        }
    }
}
```

---

## 3. Proje Yapısı

```
aesthetic-saas-backend/
├── build.gradle.kts
├── settings.gradle.kts
├── docker-compose.yml
├── Dockerfile
│
├── src/
│   ├── main/
│   │   ├── kotlin/com/aesthetic/backend/
│   │   │   │
│   │   │   ├── AestheticBackendApplication.kt          # Main entry point
│   │   │   │
│   │   │   ├── config/                                  # Konfigürasyon
│   │   │   │   ├── SecurityConfig.kt                    # Spring Security + JWT
│   │   │   │   ├── JwtConfig.kt                         # JWT token ayarları (JwtProperties)
│   │   │   │   ├── WebConfig.kt                         # CORS, interceptors, ModuleGuardInterceptor kaydı
│   │   │   │   ├── CacheConfig.kt                       # Redis cache config
│   │   │   │   ├── AsyncConfig.kt                       # Async executor + TenantAwareTaskDecorator
│   │   │   │   ├── OpenApiConfig.kt                     # Swagger UI config
│   │   │   │   └── FlywayConfig.kt                      # DB migration config
│   │   │   │
│   │   │   ├── tenant/                                  # Multi-tenant altyapısı
│   │   │   │   ├── TenantContext.kt                     # ThreadLocal tenant holder
│   │   │   │   ├── TenantFilter.kt                     # HTTP filter (subdomain/custom domain → tenant)
│   │   │   │   ├── TenantAspect.kt                     # Hibernate filter AOP
│   │   │   │   ├── TenantAwareEntity.kt                # Base entity (tenant_id + @EntityListeners)
│   │   │   │   ├── TenantEntityListener.kt             # JPA Entity Listener (auto-set tenant_id)
│   │   │   │   ├── TenantAwareTaskDecorator.kt         # Async thread'lere tenant context taşıma
│   │   │   │   ├── TenantAwareScheduler.kt             # Scheduled task'lar için tenant iteration
│   │   │   │   ├── TenantAwareCacheKeyGenerator.kt     # Redis cache key'lerine tenant prefix
│   │   │   │   └── TenantCacheManager.kt               # Tenant-scoped cache eviction
│   │   │   │
│   │   │   ├── security/                                # Auth & güvenlik
│   │   │   │   ├── JwtTokenProvider.kt                  # Token oluşturma/doğrulama
│   │   │   │   ├── JwtAuthenticationFilter.kt           # Request filter
│   │   │   │   ├── CustomUserDetailsService.kt          # UserDetails yükleme
│   │   │   │   └── RefreshToken.kt                      # Refresh token entity (revocation)
│   │   │   │
│   │   │   ├── domain/                                  # JPA Entity'ler
│   │   │   │   ├── tenant/
│   │   │   │   │   └── Tenant.kt                        # Tenant (işletme) entity
│   │   │   │   ├── user/
│   │   │   │   │   ├── User.kt
│   │   │   │   │   ├── Role.kt                          # Enum: PLATFORM_ADMIN, TENANT_ADMIN, STAFF, CLIENT
│   │   │   │   │   └── ClientNote.kt                    # Müşteri notları (TENANT_ADMIN tarafından)
│   │   │   │   ├── patient/
│   │   │   │   │   ├── PatientRecord.kt                 # Hasta/danışan kaydı (yapılandırılmış veri)
│   │   │   │   │   └── TreatmentHistory.kt              # Tedavi geçmişi
│   │   │   │   ├── service/
│   │   │   │   │   ├── Service.kt
│   │   │   │   │   └── ServiceCategory.kt
│   │   │   │   ├── product/
│   │   │   │   │   └── Product.kt
│   │   │   │   ├── blog/
│   │   │   │   │   └── BlogPost.kt
│   │   │   │   ├── gallery/
│   │   │   │   │   └── GalleryItem.kt
│   │   │   │   ├── appointment/
│   │   │   │   │   ├── Appointment.kt
│   │   │   │   │   ├── AppointmentService.kt            # Many-to-many pivot (appointment ↔ service)
│   │   │   │   │   ├── AppointmentStatus.kt             # Enum
│   │   │   │   │   ├── RecurringAppointment.kt          # Tekrarlayan randevu şablonu
│   │   │   │   │   ├── TimeSlot.kt                      # Müsait zaman dilimi
│   │   │   │   │   └── WorkingHours.kt                  # Çalışma saatleri
│   │   │   │   ├── review/
│   │   │   │   │   └── Review.kt                        # Müşteri değerlendirmeleri
│   │   │   │   ├── payment/
│   │   │   │   │   ├── Payment.kt                       # Ödeme kayıtları (iyzico)
│   │   │   │   │   ├── Subscription.kt                  # Tenant abonelik yönetimi
│   │   │   │   │   ├── SubscriptionModule.kt            # Modül add-on kayıtları
│   │   │   │   │   ├── FeatureModule.kt                 # Enum: BLOG, PRODUCTS, GALLERY, PATIENT_RECORDS, ...
│   │   │   │   │   ├── IndustryBundle.kt                # Sektör paketleri (hazır modül kombinasyonları)
│   │   │   │   │   └── Invoice.kt                       # Fatura kayıtları
│   │   │   │   ├── notification/
│   │   │   │   │   ├── Notification.kt                  # Bildirim kayıtları
│   │   │   │   │   └── NotificationTemplate.kt          # SMS/E-posta şablonları
│   │   │   │   ├── contact/
│   │   │   │   │   └── ContactMessage.kt
│   │   │   │   └── settings/
│   │   │   │       └── SiteSettings.kt
│   │   │   │
│   │   │   ├── repository/                              # Spring Data JPA Repository'ler
│   │   │   │   ├── TenantRepository.kt
│   │   │   │   ├── UserRepository.kt
│   │   │   │   ├── ServiceRepository.kt
│   │   │   │   ├── ProductRepository.kt
│   │   │   │   ├── BlogPostRepository.kt
│   │   │   │   ├── GalleryItemRepository.kt
│   │   │   │   ├── AppointmentRepository.kt
│   │   │   │   ├── ReviewRepository.kt
│   │   │   │   ├── PaymentRepository.kt
│   │   │   │   ├── SubscriptionRepository.kt
│   │   │   │   ├── SubscriptionModuleRepository.kt
│   │   │   │   ├── PatientRecordRepository.kt
│   │   │   │   ├── TreatmentHistoryRepository.kt
│   │   │   │   ├── NotificationRepository.kt
│   │   │   │   ├── RefreshTokenRepository.kt
│   │   │   │   ├── ContactMessageRepository.kt
│   │   │   │   └── SiteSettingsRepository.kt
│   │   │   │
│   │   │   ├── usecase/                                 # İş mantığı (Business Logic)
│   │   │   │   ├── TenantService.kt                     # Tenant CRUD + onboarding
│   │   │   │   ├── AuthService.kt                       # Login, register, token refresh
│   │   │   │   ├── UserService.kt                       # Kullanıcı yönetimi
│   │   │   │   ├── ServiceManagementService.kt          # Hizmet CRUD
│   │   │   │   ├── ProductService.kt                    # Ürün CRUD
│   │   │   │   ├── BlogService.kt                       # Blog CRUD
│   │   │   │   ├── GalleryService.kt                    # Galeri CRUD
│   │   │   │   ├── AppointmentService.kt                # Randevu: oluştur, iptal, tamamla
│   │   │   │   ├── AvailabilityService.kt               # Müsait slot hesaplama
│   │   │   │   ├── ReviewService.kt                     # Değerlendirme CRUD
│   │   │   │   ├── PaymentService.kt                    # Ödeme işlemleri (iyzico)
│   │   │   │   ├── SubscriptionService.kt               # Abonelik yönetimi
│   │   │   │   ├── ModuleAccessService.kt              # Modül erişim kontrolü
│   │   │   │   ├── PatientRecordService.kt             # Hasta/danışan kaydı CRUD
│   │   │   │   ├── PlanLimitService.kt                 # Plan limitleri (personel, randevu)
│   │   │   │   ├── ContactService.kt                    # İletişim mesajları
│   │   │   │   ├── SettingsService.kt                   # Site ayarları
│   │   │   │   ├── FileUploadService.kt                 # Görsel yükleme (S3/MinIO)
│   │   │   │   └── NotificationService.kt               # E-posta (SendGrid) + SMS (Netgsm)
│   │   │   │
│   │   │   ├── controller/                              # REST API Controller'lar
│   │   │   │   ├── AuthController.kt                    # /api/auth/**
│   │   │   │   ├── TenantController.kt                  # /api/platform/tenants/** (platform admin)
│   │   │   │   ├── ServiceController.kt                 # /api/admin/services/**
│   │   │   │   ├── ProductController.kt                 # /api/admin/products/**
│   │   │   │   ├── BlogController.kt                    # /api/admin/blog/**
│   │   │   │   ├── GalleryController.kt                 # /api/admin/gallery/**
│   │   │   │   ├── AppointmentController.kt             # /api/admin/appointments/**
│   │   │   │   ├── AvailabilityController.kt            # /api/public/availability + /api/admin/availability
│   │   │   │   ├── ReviewController.kt                  # /api/admin/reviews/** @RequiresModule(REVIEWS)
│   │   │   │   ├── PatientRecordController.kt           # /api/admin/patients/** @RequiresModule(PATIENT_RECORDS)
│   │   │   │   ├── PaymentController.kt                 # /api/admin/payments/** + /api/webhooks/iyzico
│   │   │   │   ├── ContactController.kt                 # /api/admin/messages/**
│   │   │   │   ├── SettingsController.kt                # /api/admin/settings/**
│   │   │   │   ├── FileUploadController.kt              # /api/admin/upload/**
│   │   │   │   └── PublicController.kt                  # /api/public/** (auth gerektirmeyen)
│   │   │   │
│   │   │   ├── dto/                                     # Request/Response DTO'lar
│   │   │   │   ├── request/
│   │   │   │   │   ├── LoginRequest.kt
│   │   │   │   │   ├── RegisterRequest.kt
│   │   │   │   │   ├── CreateServiceRequest.kt
│   │   │   │   │   ├── UpdateServiceRequest.kt
│   │   │   │   │   ├── CreateProductRequest.kt
│   │   │   │   │   ├── UpdateProductRequest.kt
│   │   │   │   │   ├── CreateBlogPostRequest.kt
│   │   │   │   │   ├── UpdateBlogPostRequest.kt
│   │   │   │   │   ├── CreateAppointmentRequest.kt
│   │   │   │   │   ├── UpdateAppointmentStatusRequest.kt
│   │   │   │   │   ├── CreateReviewRequest.kt
│   │   │   │   │   ├── CreateContactMessageRequest.kt
│   │   │   │   │   ├── UpdateSiteSettingsRequest.kt
│   │   │   │   │   └── CreateTenantRequest.kt
│   │   │   │   └── response/
│   │   │   │       ├── ApiResponse.kt                   # Standart response wrapper
│   │   │   │       ├── TokenResponse.kt                 # JWT token pair
│   │   │   │       ├── PagedResponse.kt                 # Paginated list response
│   │   │   │       ├── ServiceResponse.kt
│   │   │   │       ├── ProductResponse.kt
│   │   │   │       ├── BlogPostResponse.kt
│   │   │   │       ├── AppointmentResponse.kt
│   │   │   │       ├── AvailabilityResponse.kt          # Müsait slotlar
│   │   │   │       ├── ReviewResponse.kt
│   │   │   │       ├── PaymentResponse.kt
│   │   │   │       └── DashboardStatsResponse.kt
│   │   │   │
│   │   │   ├── mapper/                                  # Entity ↔ DTO dönüşümleri
│   │   │   │   ├── ServiceMapper.kt
│   │   │   │   ├── ProductMapper.kt
│   │   │   │   ├── BlogPostMapper.kt
│   │   │   │   ├── AppointmentMapper.kt
│   │   │   │   ├── ReviewMapper.kt
│   │   │   │   └── UserMapper.kt
│   │   │   │
│   │   │   ├── job/                                     # Scheduled Jobs
│   │   │   │   ├── AppointmentReminderJob.kt            # Randevu hatırlatıcı
│   │   │   │   ├── TrialExpirationJob.kt                # Deneme süresi kontrolü
│   │   │   │   └── StaleDataCleanupJob.kt               # Eski veri temizliği
│   │   │   │
│   │   │   └── exception/                               # Hata yönetimi
│   │   │       ├── GlobalExceptionHandler.kt            # @RestControllerAdvice (§7.8)
│   │   │       ├── TenantNotFoundException.kt           # 404 — Tenant bulunamadı
│   │   │       ├── ResourceNotFoundException.kt         # 404 — Kaynak bulunamadı
│   │   │       ├── DuplicateResourceException.kt        # 409 — Aynı kayıt var
│   │   │       ├── AppointmentConflictException.kt      # 409 — Zaman çakışması
│   │   │       ├── AccountLockedException.kt            # 423 — Hesap kilitli
│   │   │       ├── PlanLimitExceededException.kt        # 429 — Plan limiti aşıldı
│   │   │       ├── ClientBlacklistedException.kt       # 403 — Müşteri kara listede (no-show)
│   │   │       ├── NotificationDeliveryException.kt     # Bildirim gönderim hatası (@Retryable)
│   │   │       ├── PaymentException.kt                  # Ödeme hatası (iyzico)
│   │   │       └── UnauthorizedException.kt             # 401 — Yetkisiz
│   │   │
│   │   └── resources/
│   │       ├── application.yml                          # Ana konfigürasyon
│   │       ├── application-dev.yml                      # Geliştirme ortamı
│   │       ├── application-prod.yml                     # Prodüksiyon ortamı
│   │       └── db/migration/                            # Flyway migration dosyaları
│   │           ├── V1__create_tenant_table.sql
│   │           ├── V2__create_user_table.sql
│   │           ├── V3__create_service_tables.sql
│   │           ├── V4__create_product_table.sql
│   │           ├── V5__create_blog_table.sql
│   │           ├── V6__create_gallery_table.sql
│   │           ├── V7__create_appointment_tables.sql
│   │           ├── V8__create_contact_table.sql
│   │           ├── V9__create_settings_table.sql
│   │           ├── V10__create_review_table.sql
│   │           ├── V11__create_refresh_token_table.sql
│   │           ├── V12__create_payment_tables.sql
│   │           ├── V13__create_notification_tables.sql
│   │           ├── V14__create_client_notes_table.sql
│   │           ├── V15__create_audit_log_table.sql
│   │           └── V16__create_consent_records_table.sql
│   │
│   └── test/
│       └── kotlin/com/aesthetic/backend/
│           ├── usecase/
│           │   ├── AppointmentServiceTest.kt            # Randevu iş mantığı testleri
│           │   ├── AvailabilityServiceTest.kt           # Müsaitlik testleri
│           │   └── PaymentServiceTest.kt                # Ödeme iş mantığı testleri
│           ├── controller/
│           │   ├── AppointmentControllerTest.kt         # API entegrasyon testleri
│           │   ├── AuthControllerTest.kt
│           │   └── PaymentControllerTest.kt             # Ödeme API testleri
│           ├── repository/
│           │   └── AppointmentRepositoryTest.kt         # DB testleri (Testcontainers)
│           └── tenant/
│               └── TenantIsolationTest.kt               # Tenant izolasyon testleri

```

---

## 4. Entity / Model Tasarımı (JPA)

### 4.1 Tenant (İşletme)

```kotlin
@Entity
@Table(name = "tenants")
class Tenant(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(unique = true, nullable = false)
    val slug: String,                       // subdomain: "salon1" → salon1.app.com

    @Column(nullable = false)
    var name: String,                       // "Güzellik Merkezi Ayşe"

    @Enumerated(EnumType.STRING)
    var businessType: BusinessType,         // BEAUTY_CLINIC, DENTAL, BARBER, HAIR_SALON

    var phone: String = "",
    var email: String = "",
    var address: String = "",
    var logoUrl: String? = null,

    @Column(unique = true)
    var customDomain: String? = null,       // Özel domain (opsiyonel): "salongüzellik.com"

    @Enumerated(EnumType.STRING)
    var plan: SubscriptionPlan = SubscriptionPlan.TRIAL,

    var trialEndDate: LocalDate? = null,    // Trial bitiş tarihi (TRIAL planında zorunlu)

    var isActive: Boolean = true,

    @CreationTimestamp
    val createdAt: Instant? = null,              // DÜZELTME: LocalDateTime → Instant (UTC)

    @UpdateTimestamp
    var updatedAt: Instant? = null               // DÜZELTME: LocalDateTime → Instant (UTC)
)

enum class BusinessType {
    BEAUTY_CLINIC,      // Güzellik kliniği
    DENTAL_CLINIC,      // Diş kliniği
    BARBER_SHOP,        // Berber
    HAIR_SALON,         // Kuaför salonu
    DIETITIAN,          // Diyetisyen
    PHYSIOTHERAPIST,    // Fizyoterapist
    MASSAGE_SALON,      // Masaj salonu
    VETERINARY,         // Veteriner kliniği
    GENERAL             // Genel (sektör belirtmeyen işletmeler)
}

// Sektör bazlı önerilen modül kombinasyonları — onboarding sırasında varsayılan olarak önerilir
val RECOMMENDED_MODULES: Map<BusinessType, Set<FeatureModule>> = mapOf(
    BusinessType.BEAUTY_CLINIC to setOf(
        FeatureModule.BLOG, FeatureModule.GALLERY, FeatureModule.PRODUCTS, FeatureModule.REVIEWS
    ),
    BusinessType.DENTAL_CLINIC to setOf(
        FeatureModule.PATIENT_RECORDS, FeatureModule.GALLERY
    ),
    BusinessType.BARBER_SHOP to setOf(
        FeatureModule.GALLERY
    ),
    BusinessType.HAIR_SALON to setOf(
        FeatureModule.GALLERY, FeatureModule.PRODUCTS
    ),
    BusinessType.DIETITIAN to setOf(
        FeatureModule.PATIENT_RECORDS
    ),
    BusinessType.PHYSIOTHERAPIST to setOf(
        FeatureModule.PATIENT_RECORDS
    ),
    BusinessType.MASSAGE_SALON to setOf(
        FeatureModule.GALLERY, FeatureModule.REVIEWS
    ),
    BusinessType.VETERINARY to setOf(
        FeatureModule.PATIENT_RECORDS
    ),
    BusinessType.GENERAL to emptySet()
)

enum class SubscriptionPlan {
    TRIAL,              // 14 gün deneme (tüm modüller açık — değerlendirme amaçlı)
    STARTER,            // Temel paket: 1 personel, 100 randevu/ay, 500MB
    PROFESSIONAL,       // Profesyonel: 5 personel, 500 randevu/ay, 2GB
    BUSINESS,           // İşletme: 15 personel, 2000 randevu/ay, 5GB
    ENTERPRISE          // Kurumsal: Sınırsız, özel destek, 20GB
}
```

### 4.2 User (Kullanıcı)

```kotlin
@Entity
@Table(
    name = "users",
    uniqueConstraints = [
        // Email tenant bazlı unique — farklı tenant'larda aynı email olabilir
        UniqueConstraint(name = "uk_user_email_tenant", columnNames = ["email", "tenant_id"])
    ]
)
class User : TenantAwareEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @Column(name = "first_name", nullable = false, length = 100)
    var firstName: String = ""

    @Column(name = "last_name", nullable = false, length = 100)
    var lastName: String = ""

    @Column(nullable = false)
    var email: String = ""

    @Column(nullable = true)                        // STAFF rolü için TENANT_ADMIN şifre belirler (kısıtlı login)
    var passwordHash: String? = null

    var phone: String? = null
    var image: String? = null
    @Column(length = 100)
    var title: String? = null                        // Personel unvanı ("Dr.", "Uzm." vb.)

    @Enumerated(EnumType.STRING)
    var role: Role = Role.CLIENT

    var isActive: Boolean = true
    var forcePasswordChange: Boolean = false          // DÜZELTME T7: Onboarding'de geçici şifre → ilk girişte değiştirmeli

    // Güvenlik: başarısız giriş denemesi sayacı (brute force koruması)
    // NOT: STAFF dahil tüm roller için kullanılır (kısıtlı login)
    var failedLoginAttempts: Int = 0
    var lockedUntil: Instant? = null               // DÜZELTME: LocalDateTime → Instant (UTC)

    // No-show takibi — CLIENT rolü için (3 kez gelmezse randevu oluşturamaz)
    var noShowCount: Int = 0                       // Toplam no-show sayısı
    var isBlacklisted: Boolean = false             // Kara liste durumu
    var blacklistedAt: Instant? = null             // Ne zaman engellendi
    var blacklistReason: String? = null            // "3 kez randevuya gelmedi"

    @CreationTimestamp
    val createdAt: Instant? = null                 // DÜZELTME: LocalDateTime → Instant (UTC)

    @UpdateTimestamp
    var updatedAt: Instant? = null                 // DÜZELTME: LocalDateTime → Instant (UTC)

    // İlişkiler
    @OneToMany(mappedBy = "client", fetch = FetchType.LAZY)
    val clientAppointments: MutableList<Appointment> = mutableListOf()

    @OneToMany(mappedBy = "staff", fetch = FetchType.LAZY)
    val staffAppointments: MutableList<Appointment> = mutableListOf()

    // Müşteri notları (personel tarafından eklenir)
    @OneToMany(mappedBy = "client", fetch = FetchType.LAZY)
    val notes: MutableList<ClientNote> = mutableListOf()

    // DÜZELTME F1: Personelin sunduğu hizmetler (staff-service ilişkisi)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "staff_services",
        joinColumns = [JoinColumn(name = "staff_id")],
        inverseJoinColumns = [JoinColumn(name = "service_id")]
    )
    val services: MutableSet<Service> = mutableSetOf()
}

enum class Role {
    PLATFORM_ADMIN,     // Tüm platforma erişim (SaaS sahibi) — Login: ✅
    TENANT_ADMIN,       // İşletme sahibi — tüm tenant verilerine erişim — Login: ✅
    STAFF,              // Personel — Login: ✅ (kısıtlı, read-only)
                        //   TENANT_ADMIN tarafından eklenen personel kaydı.
                        //   Kendi takvimi ve randevularını görebilir (read-only).
                        //   Admin, personel adına işlem yapabilir.
                        //   Refresh token süresi: 7 gün.
    CLIENT              // Müşteri — kendi randevuları, profili — Login: ✅
}

// NOT: PLATFORM_ADMIN kullanıcıları da users tablosunda tutulur ancak özel tenant_id
// değeri olarak sabit "PLATFORM" kullanılır. TenantAspect bu değeri gördüğünde
// Hibernate filter'ı devre dışı bırakır (tüm tenant verilerine erişim).
// Alternatif: Ayrı bir platform_admins tablosu oluşturulabilir ancak bu, auth
// sistemini karmaşıklaştırır. Tek tablo + magic tenant_id daha pragmatiktir.

// NOT: STAFF kısıtlı login yapar (read-only). TENANT_ADMIN tarafından oluşturulan bir personel
// profilidir. STAFF de login yapabilir (kısıtlı, read-only). TENANT_ADMIN şifre belirler.
// TENANT_ADMIN personellerini kendi admin panelinden ekler/düzenler/siler.

// ClientNote.kt — Personel'in müşteri hakkında özel notları
@Entity
@Table(name = "client_notes")
class ClientNote : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    lateinit var client: User          // Not hangi müşteriye ait

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    lateinit var author: User          // Notu yazan personel

    @Column(columnDefinition = "TEXT")
    var content: String = ""          // "Lateks alerjisi var", "Sol omuz hassas"

    @CreationTimestamp
    val createdAt: Instant? = null               // DÜZELTME: LocalDateTime → Instant (UTC)
}
```

### 4.2.1 PatientRecord & TreatmentHistory (Hasta/Danışan Kaydı Modülü)

> **Modül:** `FeatureModule.PATIENT_RECORDS` — Opsiyonel add-on modül.
> **Kullanım:** Diş kliniği, diyetisyen, fizyoterapist gibi yapılandırılmış hasta verisi gerektiren sektörler.
> **Fark:** `ClientNote` düz metin nottur. `PatientRecord` yapılandırılmış veri (JSON) tutar.

```kotlin
@Entity
@Table(
    name = "patient_records",
    uniqueConstraints = [
        // Bir müşterinin bir tenant'ta tek kaydı olabilir
        UniqueConstraint(name = "uk_patient_client_tenant", columnNames = ["client_id", "tenant_id"])
    ]
)
class PatientRecord : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    lateinit var client: User                  // Hangi müşteriye ait

    // Yapılandırılmış tıbbi veriler — typed alanlar (JSON yerine)
    var bloodType: String? = null              // "A+", "B-", "0+" vb.
    @Column(columnDefinition = "TEXT")
    var allergies: String = ""                 // "Lateks, Penisilin"
    @Column(columnDefinition = "TEXT")
    var chronicConditions: String = ""         // "Diyabet, Hipertansiyon"
    @Column(columnDefinition = "TEXT")
    var currentMedications: String = ""        // "Metformin 500mg"
    @Column(columnDefinition = "TEXT")
    var medicalNotes: String = ""              // Genel tıbbi notlar

    // Sektöre özgü ek veri (JSON) — typed alanlara sığmayan ekstralar
    @Column(columnDefinition = "JSON")
    var extraData: String? = null              // {"height":175,"weight":80,"targetWeight":70}

    @CreationTimestamp val createdAt: Instant? = null
    @UpdateTimestamp var updatedAt: Instant? = null
}

@Entity
@Table(
    name = "treatment_history",
    indexes = [
        Index(name = "idx_treatment_patient", columnList = "patient_record_id"),
        Index(name = "idx_treatment_date", columnList = "tenant_id, treatment_date DESC")
    ]
)
class TreatmentHistory : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    lateinit var client: User                     // Hangi müşteriye ait

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by")
    var performedBy: User? = null                 // Tedaviyi yapan personel (STAFF veya TENANT_ADMIN)

    var treatmentDate: LocalDate = LocalDate.now()
    var title: String = ""                        // "Dolgu uygulaması", "Diyet planı güncelleme"

    @Column(columnDefinition = "TEXT")
    var description: String = ""                  // Detaylı açıklama

    @Column(columnDefinition = "TEXT")
    var notes: String = ""                        // Ek notlar

    var beforeImage: String? = null               // Tedavi öncesi fotoğraf URL
    var afterImage: String? = null                // Tedavi sonrası fotoğraf URL

    @CreationTimestamp val createdAt: Instant? = null
}
```

**PatientRecordRepository:**

```kotlin
interface PatientRecordRepository : JpaRepository<PatientRecord, String> {
    fun findByClientIdAndTenantId(clientId: String, tenantId: String): PatientRecord?
    fun findByTenantId(tenantId: String, pageable: Pageable): Page<PatientRecord>
}

interface TreatmentHistoryRepository : JpaRepository<TreatmentHistory, String> {
    fun findAllByClientIdAndTenantId(
        clientId: String, tenantId: String, pageable: Pageable
    ): Page<TreatmentHistory>
}
```

### 4.3 Service (Hizmet)

```kotlin
@Entity
@Table(
    name = "services",
    uniqueConstraints = [
        // Slug tenant bazlı unique — farklı tenant'larda aynı slug olabilir
        UniqueConstraint(name = "uk_service_slug_tenant", columnNames = ["slug", "tenant_id"])
    ]
)
class Service : TenantAwareEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @Column(nullable = false)
    var slug: String = ""

    @Column(nullable = false)
    var title: String = ""

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    var category: ServiceCategory? = null

    var shortDescription: String = ""

    @Column(columnDefinition = "TEXT")
    var description: String = ""

    @Column(precision = 10, scale = 2)    // KRİTİK: Ondalık hassasiyet
    var price: BigDecimal = BigDecimal.ZERO
    var currency: String = "TRY"

    var durationMinutes: Int = 30          // Dakika cinsinden süre
    var bufferMinutes: Int = 0             // Randevu arası tampon süresi

    var image: String? = null

    @ElementCollection
    @CollectionTable(name = "service_benefits", joinColumns = [JoinColumn(name = "service_id")])
    @Column(name = "benefit")
    @OrderColumn(name = "sort_order")    // DÜZELTME D1: Sıralama korunması (delete+re-insert önlenir)
    var benefits: MutableList<String> = mutableListOf()

    @ElementCollection
    @CollectionTable(name = "service_process_steps", joinColumns = [JoinColumn(name = "service_id")])
    @Column(name = "step")
    @OrderColumn(name = "sort_order")    // DÜZELTME D1: Sıralama korunması
    var processSteps: MutableList<String> = mutableListOf()

    @Column(columnDefinition = "TEXT")
    var recovery: String? = null

    var isActive: Boolean = true
    var sortOrder: Int = 0

    // SEO
    var metaTitle: String? = null
    var metaDescription: String? = null
    var ogImage: String? = null

    @CreationTimestamp
    val createdAt: Instant? = null                 // DÜZELTME: LocalDateTime → Instant (UTC)

    @UpdateTimestamp
    var updatedAt: Instant? = null                 // DÜZELTME: LocalDateTime → Instant (UTC)
}

// ServiceCategory.kt — Hizmet kategorileri
@Entity
@Table(
    name = "service_categories",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_category_slug_tenant", columnNames = ["slug", "tenant_id"])
    ]
)
class ServiceCategory : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    var slug: String = ""               // "yuz-bakimi", "lazer"
    var name: String = ""               // "Yüz Bakımı", "Lazer Epilasyon"
    var description: String? = null
    var image: String? = null
    var sortOrder: Int = 0
    var isActive: Boolean = true

    @OneToMany(mappedBy = "category", fetch = FetchType.LAZY)
    val services: MutableList<Service> = mutableListOf()

    @CreationTimestamp val createdAt: Instant? = null   // DÜZELTME: LocalDateTime → Instant (UTC)
    @UpdateTimestamp var updatedAt: Instant? = null     // DÜZELTME: LocalDateTime → Instant (UTC)
}
```

### 4.4 Appointment (Randevu) — KRİTİK

```kotlin
@Entity
@Table(
    name = "appointments",
    indexes = [
        Index(name = "idx_appt_tenant_date", columnList = "tenant_id, date"),
        Index(name = "idx_appt_conflict", columnList = "tenant_id, staff_id, date, start_time, end_time, status"),
        Index(name = "idx_appt_status", columnList = "tenant_id, status"),
        Index(name = "idx_appt_client", columnList = "tenant_id, client_id")
    ]
)
class Appointment : TenantAwareEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    // Müşteri bilgileri — Snapshot Pattern (AA-O12):
    // Hem `client` referansı hem string alanları tutulur. Müşteri bilgisi değişse bile
    // randevu kaydı oluşturma anındaki bilgiyi korur (fatura/rapor doğruluğu için).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    var client: User? = null               // Kayıtlı müşteri (nullable: misafir randevu)

    var clientName: String = ""             // Snapshot: oluşturma anındaki müşteri adı
    var clientEmail: String = ""            // Snapshot: oluşturma anındaki e-posta
    var clientPhone: String = ""            // Snapshot: oluşturma anındaki telefon

    // Randevu hizmetleri — Çoklu hizmet desteği (örn: "Saç boyama + Kesim + Fön")
    @OneToMany(mappedBy = "appointment", cascade = [CascadeType.ALL], orphanRemoval = true)
    var services: MutableList<AppointmentService> = mutableListOf()

    // Birincil hizmet (geriye uyumluluk + basit sorgular için)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_service_id")
    var primaryService: Service? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id")
    var staff: User? = null                // Atanan personel

    @Column(nullable = false)
    var date: LocalDate = LocalDate.now()

    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime = LocalTime.now()

    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime = LocalTime.now()

    // Toplam süre ve fiyat (tüm hizmetlerin toplamı)
    var totalDurationMinutes: Int = 0

    @Column(precision = 10, scale = 2)
    var totalPrice: BigDecimal = BigDecimal.ZERO

    @Column(columnDefinition = "TEXT")
    var notes: String? = null

    @Enumerated(EnumType.STRING)
    var status: AppointmentStatus = AppointmentStatus.PENDING

    // İptal bilgisi
    var cancelledAt: Instant? = null               // DÜZELTME: LocalDateTime → Instant (UTC)
    var cancellationReason: String? = null

    // Tekrarlayan randevu desteği
    var recurringGroupId: String? = null        // Tekrarlayan randevuları gruplar
    var recurrenceRule: String? = null           // "WEEKLY", "BIWEEKLY", "MONTHLY"

    // Hatırlatıcı durumu
    var reminder24hSent: Boolean = false
    var reminder1hSent: Boolean = false

    @CreationTimestamp
    val createdAt: Instant? = null                 // DÜZELTME: LocalDateTime → Instant (UTC)

    @UpdateTimestamp
    var updatedAt: Instant? = null                 // DÜZELTME: LocalDateTime → Instant (UTC)

    // NOT AA-O5: @Version (optimistic locking) + PESSIMISTIC_WRITE birlikte kullanılıyor.
    // PESSIMISTIC_WRITE zaten satır seviyesinde kilit alır, @Version gereksiz overhead ekler.
    // Gelecekte @Version kaldırılabilir — PESSIMISTIC_WRITE tek başına yeterlidir.
    @Version
    var version: Long = 0
}

// AppointmentService.kt — Randevu-Hizmet ilişkisi (çoklu hizmet desteği)
// KRİTİK: TenantAwareEntity'den extend etmeli! Aksi halde Hibernate filter uygulanmaz
// ve cross-tenant sorgularda yanlış tenant'ın appointment_services satırları dönebilir.
@Entity
@Table(name = "appointment_services")
class AppointmentService : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false)
    var appointment: Appointment? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    var service: Service? = null

    @Column(precision = 10, scale = 2)
    var price: BigDecimal = BigDecimal.ZERO       // Randevu anındaki fiyat (snapshot)

    var durationMinutes: Int = 0                   // Randevu anındaki süre (snapshot)
    var sortOrder: Int = 0                         // Sıralama
}

enum class AppointmentStatus {
    PENDING,            // Onay bekliyor
    CONFIRMED,          // Onaylandı
    IN_PROGRESS,        // Devam ediyor
    COMPLETED,          // Tamamlandı
    CANCELLED,          // İptal edildi
    NO_SHOW             // Gelmedi
}

// Durum geçiş kuralları:
// PENDING    → CONFIRMED, CANCELLED
// CONFIRMED  → IN_PROGRESS, CANCELLED, NO_SHOW
// IN_PROGRESS → COMPLETED
// COMPLETED  → (son durum)
// CANCELLED  → (son durum)
// NO_SHOW    → (son durum)
```

### 4.5 WorkingHours (Çalışma Saatleri)

```kotlin
// KRİTİK DÜZELTME: TenantAwareEntity'den extend ediyor.
// Önceki versiyon manuel tenant_id + @Filter tanımlıyordu ama @FilterDef ve
// @EntityListeners eksikti → filter çalışmaz, tenant_id auto-set olmazdı.
@Entity
@Table(
    name = "working_hours",
    indexes = [
        Index(name = "idx_wh_tenant_staff", columnList = "tenant_id, staff_id, day_of_week")
    ]
)
class WorkingHours : TenantAwareEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id")
    var staff: User? = null                 // null ise genel işletme saatleri

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false)
    var dayOfWeek: DayOfWeek = DayOfWeek.MONDAY

    @Column(nullable = false)
    var startTime: LocalTime = LocalTime.of(9, 0)

    @Column(nullable = false)
    var endTime: LocalTime = LocalTime.of(18, 0)

    var breakStartTime: LocalTime? = null   // 12:00 (öğle arası)
    var breakEndTime: LocalTime? = null     // 13:00

    var isWorkingDay: Boolean = true        // false = kapalı gün
}
```

### 4.6 BlockedTimeSlot (Bloklanmış Zaman Dilimi)

```kotlin
// KRİTİK DÜZELTME: TenantAwareEntity'den extend ediyor (aynı WorkingHours sorunu).
@Entity
@Table(
    name = "blocked_time_slots",
    indexes = [
        Index(name = "idx_bts_tenant_staff_date", columnList = "tenant_id, staff_id, date")
    ]
)
class BlockedTimeSlot : TenantAwareEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id")
    var staff: User? = null

    @Column(nullable = false)
    var date: LocalDate = LocalDate.now()

    @Column(nullable = false)
    var startTime: LocalTime = LocalTime.of(9, 0)

    @Column(nullable = false)
    var endTime: LocalTime = LocalTime.of(10, 0)

    var reason: String? = null               // "Tatil", "Toplantı" vb.
}
```

### 4.7 Diğer Entity'ler

> **NOT:** Tüm entity'ler `TenantAwareEntity`'den extend eder. `@FilterDef` + `@Filter` miras alınır. `@CreationTimestamp` / `@UpdateTimestamp` ile tarihler otomatik yönetilir. `@Column(precision=10, scale=2)` ile fiyatlar doğru saklanır.

```kotlin
// Product.kt
@Entity
@Table(
    name = "products",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_product_slug_tenant", columnNames = ["slug", "tenant_id"])
    ]
)
class Product : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null
    var slug: String = ""
    var title: String = ""
    var shortDescription: String = ""
    @Column(columnDefinition = "TEXT") var description: String = ""
    @Column(precision = 10, scale = 2) var price: BigDecimal = BigDecimal.ZERO
    var currency: String = "TRY"
    var image: String? = null
    @ElementCollection
    @CollectionTable(name = "product_features", joinColumns = [JoinColumn(name = "product_id")])
    @Column(name = "feature")
    @OrderColumn(name = "sort_order")
    var features: MutableList<String> = mutableListOf()
    var stockQuantity: Int? = null          // null = stok takibi yok
    var isActive: Boolean = true
    var sortOrder: Int = 0
    var seoTitle: String? = null
    var seoDescription: String? = null
    var ogImage: String? = null
    @CreationTimestamp val createdAt: Instant? = null
    @UpdateTimestamp var updatedAt: Instant? = null
}

// BlogPost.kt
@Entity
@Table(
    name = "blog_posts",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_blog_slug_tenant", columnNames = ["slug", "tenant_id"])
    ]
)
class BlogPost : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null
    var slug: String = ""
    var title: String = ""
    @Column(columnDefinition = "TEXT") var summary: String = ""
    @Column(columnDefinition = "LONGTEXT") var content: String = ""
    var coverImage: String? = null

    // Yazar: User entity referansı + snapshot (authorName)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    var author: User? = null
    var authorName: String = ""            // Snapshot — yazar adı değişse bile korunur

    @ElementCollection
    @CollectionTable(name = "blog_post_tags", joinColumns = [JoinColumn(name = "blog_post_id")])
    @Column(name = "tag")
    @OrderColumn(name = "sort_order")
    var tags: MutableList<String> = mutableListOf()
    // readTime: DB'de saklanmaz, BlogPostMapper.toResponse() içinde runtime'da hesaplanır
    // (kelime sayısı / 200 WPM → "$minutes dk okuma")
    var isPublished: Boolean = false
    var publishedAt: Instant? = null
    var seoTitle: String? = null
    var seoDescription: String? = null
    var ogImage: String? = null
    @CreationTimestamp val createdAt: Instant? = null
    @UpdateTimestamp var updatedAt: Instant? = null
}

// GalleryItem.kt
@Entity
@Table(name = "gallery_items")
class GalleryItem : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null
    var title: String = ""
    var description: String = ""
    var imageUrl: String = ""                     // Ana görsel URL
    var beforeImageUrl: String? = null            // Öncesi fotoğrafı (opsiyonel)
    var afterImageUrl: String? = null             // Sonrası fotoğrafı (opsiyonel)
    var isActive: Boolean = true
    var sortOrder: Int = 0
    var category: String = ""

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    var service: Service? = null                  // İlişkili hizmet (opsiyonel)

    @CreationTimestamp val createdAt: Instant? = null
    @UpdateTimestamp var updatedAt: Instant? = null
}

// ContactMessage.kt
@Entity
@Table(name = "contact_messages")
class ContactMessage : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null
    var name: String = ""
    var email: String = ""
    var phone: String = ""
    var subject: String = ""
    @Column(columnDefinition = "TEXT") var message: String = ""
    var isRead: Boolean = false
    var readAt: Instant? = null                    // Okunma zamanı
    @CreationTimestamp val createdAt: Instant? = null
    @UpdateTimestamp var updatedAt: Instant? = null
}

// SiteSettings.kt — workingHoursJson KALDIRILDI (WorkingHours entity'si kullanılıyor)
@Entity
@Table(name = "site_settings")
class SiteSettings : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null
    var siteName: String = ""
    var phone: String = ""
    var email: String = ""
    var address: String = ""
    var whatsapp: String = ""
    var instagram: String = ""
    var facebook: String = ""
    var twitter: String = ""
    var youtube: String = ""
    var mapEmbedUrl: String = ""
    var timezone: String = "Europe/Istanbul"   // Tenant bazlı timezone desteği
    var locale: String = "tr"                  // Dil ayarı
    var cancellationPolicyHours: Int = 24      // Ücretsiz iptal süresi (saat)
    var defaultSlotDurationMinutes: Int = 30   // Varsayılan randevu süresi (dakika)

    // Tema özelleştirme — JSON yapısı:
    // {
    //   "logoUrl": "/uploads/tenant-uuid/logo.png",
    //   "faviconUrl": "/uploads/tenant-uuid/favicon.ico",
    //   "primaryColor": "#e03aff",
    //   "secondaryColor": "#14b8a6",
    //   "fontFamily": "Inter",
    //   "headerStyle": "fixed",          // "fixed" | "static"
    //   "heroImageUrl": "/uploads/tenant-uuid/hero.jpg"
    // }
    @Column(columnDefinition = "JSON")
    var themeSettings: String = "{}"
}

// Review.kt — Müşteri değerlendirme sistemi
@Entity
@Table(name = "reviews")
class Review : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    var appointment: Appointment? = null      // Hangi randevu sonrası (opsiyonel)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    var service: Service? = null              // Hangi hizmet için (opsiyonel)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    var client: User? = null

    var clientName: String = ""               // Snapshot — müşteri adı değişse bile korunur

    @Column(nullable = false)
    var rating: Int = 0                        // 1-5 yıldız
    @Column(columnDefinition = "TEXT")
    var comment: String = ""

    @Enumerated(EnumType.STRING)
    var approvalStatus: ReviewApprovalStatus = ReviewApprovalStatus.PENDING  // PENDING, APPROVED, REJECTED

    @Column(columnDefinition = "TEXT")
    var adminResponse: String? = null          // Admin yanıtı
    var adminResponseAt: Instant? = null       // Admin yanıt zamanı

    @CreationTimestamp val createdAt: Instant? = null
    @UpdateTimestamp var updatedAt: Instant? = null
}
```

---

## 5. Randevu Sistemi — Çift Randevu Engelleme

Bu sistemin en kritik parçasıdır. Aynı personel, aynı zaman diliminde iki randevu alamamalıdır.

### 5.1 Strateji: READ_COMMITTED + Pessimistic Write Lock

> **KRİTİK DÜZELTME:** Önceki versiyonda `SERIALIZABLE` isolation + `PESSIMISTIC_WRITE` birlikte kullanılıyordu. MySQL InnoDB'de bu deadlock'a neden olur: SERIALIZABLE tüm SELECT'leri `LOCK IN SHARE MODE`'a çevirir, sonra `FOR UPDATE`'e yükseltme girişiminde her iki transaction karşılıklı bloklanır. Doğru yaklaşım: **`READ_COMMITTED` + `PESSIMISTIC_WRITE` (SELECT ... FOR UPDATE)**.

```
Randevu oluşturma akışı:

  Müşteri "14:00 Botoks" seçer
           │
           ▼
  ① Müsaitlik kontrolü (READ — lock yok, UI için)
     → availabilityService.getAvailableSlots(date, serviceId, staffId)
           │
           ▼
  ② Randevu oluşturma isteği
     → POST /api/appointments
           │
           ▼
  ③ Transaction başlat (READ_COMMITTED)
           │
           ▼
  ④ Geçmiş tarih kontrolü
     → request.date < bugün? → 400 Bad Request
           │
           ▼
  ⑤ Pessimistic Lock ile çakışma kontrolü
     → SELECT ... FOR UPDATE (satır kilidi)
     → Buffer time dahil: endTime + bufferMinutes
     → Aynı staff + tarih + saat aralığında aktif randevu var mı?
           │
     ┌─────┴──────┐
     │ VAR         │ YOK
     │             │
     ▼             ▼
  HATA döndür   ⑥ İptal politikası kontrolü (opsiyonel)
  409 Conflict          │
                        ▼
                  ⑦ Randevu kaydet + hizmetleri ekle
                     → appointmentRepository.save()
                         │
                         ▼
                  ⑧ Bildirim gönder (@Async — TenantAwareTaskDecorator ile)
                     → E-posta + SMS
                         │
                         ▼
                  ⑨ 201 Created döndür
```

### 5.2 AppointmentService Implementasyonu

```kotlin
@Service
class AppointmentService(
    private val appointmentRepository: AppointmentRepository,
    private val appointmentServiceRepository: AppointmentServiceRepository,
    private val availabilityService: AvailabilityService,
    private val serviceRepository: ServiceRepository,
    private val settingsRepository: SiteSettingsRepository,
    private val notificationService: NotificationService,
    private val userRepository: UserRepository,        // Blacklist kontrolü + no-show sayacı
    private val entityManager: EntityManager           // getReference() için gerekli
) {
    companion object {
        const val NO_SHOW_BLACKLIST_THRESHOLD = 3      // 3 kez gelmezse kara liste
    }
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Randevu oluşturma — Pessimistic Locking ile çift randevu engelleme
     *
     * İzolasyon seviyesi: READ_COMMITTED (explicit, MySQL default ile aynı)
     * SERIALIZABLE KULLANMA! Deadlock'a neden olur.
     * PESSIMISTIC_WRITE (@Lock) tek başına yeterli.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun createAppointment(request: CreateAppointmentRequest): Appointment {
        val tenantId = TenantContext.getTenantId()

        // ── ⓪ Müşteri kara liste kontrolü ──
        // 3 kez randevuya gelmeyen müşteri yeni randevu oluşturamaz
        val existingClient = userRepository.findByEmailAndTenantId(request.clientEmail, tenantId)
        if (existingClient?.isBlacklisted == true) {
            throw ClientBlacklistedException(
                "Bu müşteri daha önce ${existingClient.noShowCount} kez randevuya gelmediği için " +
                "randevu oluşturulamaz. Lütfen işletmeyle iletişime geçin."
            )
        }

        // ── ① Geçmiş tarih kontrolü ──
        // KRİTİK: Tenant'ın timezone'unu kullan, sunucu timezone'u DEĞİL!
        val settings = settingsRepository.findByTenantId(tenantId)
        val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")
        val now = ZonedDateTime.now(tenantZone)
        val today = now.toLocalDate()
        val currentTime = now.toLocalTime()

        if (request.date.isBefore(today)) {
            throw IllegalArgumentException("Geçmiş bir tarihe randevu oluşturulamaz")
        }
        if (request.date == today && request.startTime.isBefore(currentTime)) {
            throw IllegalArgumentException("Geçmiş bir saate randevu oluşturulamaz")
        }

        // ── ② Hizmetleri yükle ve toplam süre/fiyat hesapla ──
        val serviceIds = request.serviceIds  // Çoklu hizmet desteği
        val services = serviceRepository.findAllById(serviceIds)
        if (services.size != serviceIds.size) {
            throw ResourceNotFoundException("Bir veya daha fazla hizmet bulunamadı")
        }

        val totalDuration = services.sumOf { it.durationMinutes }
        // Buffer time: sıralı hizmetlerde SON hizmetin buffer'ı kullanılır.
        // Ara hizmetlerin buffer'ı anlamsız çünkü sonraki hizmet hemen başlar.
        val totalBuffer = services.lastOrNull()?.bufferMinutes ?: 0
        val totalPrice = services.sumOf { it.price }
        val startTime = request.startTime
        val endTime = startTime.plusMinutes(totalDuration.toLong())
        val endTimeWithBuffer = endTime.plusMinutes(totalBuffer.toLong())

        // ── ③ Personel belirleme ──
        // Eğer staffId null ise → "herhangi bir müsait personel" senaryosu.
        // Tüm aktif personeli iterate et, ilk müsait olanı ata.
        val resolvedStaffId: String = if (request.staffId != null) {
            request.staffId
        } else {
            availabilityService.findAvailableStaff(
                tenantId, request.date, startTime, endTimeWithBuffer
            ) ?: throw AppointmentConflictException(
                "Bu zaman diliminde müsait personel bulunamadı. " +
                "Lütfen başka bir saat seçin."
            )
        }

        // ── ④ Pessimistic Lock ile çakışma kontrolü ──
        // READ_COMMITTED + FOR UPDATE: İkinci transaction ilkinin bitmesini bekler
        // staffId artık non-null zorunlu (yukarıda resolve edildi)
        val conflicts = appointmentRepository.findConflictingAppointmentsWithLock(
            tenantId = tenantId,
            staffId = resolvedStaffId,
            date = request.date,
            startTime = startTime,
            endTime = endTimeWithBuffer   // Buffer time dahil
        )

        if (conflicts.isNotEmpty()) {
            throw AppointmentConflictException(
                "Bu zaman diliminde zaten bir randevu mevcut. " +
                "Lütfen başka bir saat seçin."
            )
        }

        // ── ⑤ Çalışma saatleri kontrolü ──
        if (!availabilityService.isWithinWorkingHours(
                tenantId, resolvedStaffId, request.date, startTime, endTime)) {
            throw IllegalArgumentException("Seçilen saat çalışma saatleri dışında")
        }

        // ── ⑥ Bloklanmış zaman kontrolü ──
        if (availabilityService.isTimeSlotBlocked(
                tenantId, resolvedStaffId, request.date, startTime, endTime)) {
            throw AppointmentConflictException("Bu zaman dilimi bloklanmış")
        }

        // ── ⑥ Randevu oluştur ──
        // NOT: tenantId set etmeye GEREK YOK — TenantEntityListener.onPrePersist otomatik set eder.
        val appointment = Appointment().apply {
            this.clientName = request.clientName
            this.clientEmail = request.clientEmail
            this.clientPhone = request.clientPhone
            this.primaryService = services.first()
            // KRİTİK: User.id val (read-only), yeni User() ile id atayamazsın!
            // entityManager.getReference() lazy proxy döndürür, DB'ye gitmez.
            this.staff = entityManager.getReference(User::class.java, resolvedStaffId)
            this.date = request.date
            this.startTime = startTime
            this.endTime = endTime
            this.totalDurationMinutes = totalDuration
            this.totalPrice = totalPrice
            this.notes = request.notes
            this.status = AppointmentStatus.PENDING
        }

        val saved = appointmentRepository.save(appointment)

        // ── ⑦ Hizmetleri randevuya bağla (fiyat snapshot) ──
        services.forEachIndexed { index, service ->
            val apptService = AppointmentService().apply {
                this.appointment = saved
                this.service = service
                this.price = service.price          // Randevu anındaki fiyat
                this.durationMinutes = service.durationMinutes
                this.sortOrder = index
            }
            appointmentServiceRepository.save(apptService)
        }

        logger.info("[tenant={}] Randevu oluşturuldu: {} ({})",
            tenantId, saved.id, services.map { it.title })

        // ── ⑧ Bildirim gönder (async — TenantAwareTaskDecorator ile) ──
        // DÜZELTME: Entity yerine DTO (lazy loading güvenli)
        val ctx = notificationService.toContext(saved)
        notificationService.sendAppointmentConfirmation(ctx)

        return saved
    }

    /**
     * Randevu iptali — İptal politikası kontrolü ile
     */
    @Transactional
    fun cancelAppointment(id: String, reason: String?, cancelledByClient: Boolean = false): Appointment {
        val appointment = appointmentRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Randevu bulunamadı: $id") }

        validateStatusTransition(appointment.status, AppointmentStatus.CANCELLED)

        // Müşteri iptali ise iptal politikası kontrolü
        if (cancelledByClient) {
            val settings = settingsRepository.findByTenantId(TenantContext.getTenantId())
            // DÜZELTME: Tenant timezone ile karşılaştır (server timezone ≠ tenant timezone)
            val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")
            val cancellationDeadline = appointment.date.atTime(appointment.startTime)
                .minusHours(settings?.cancellationPolicyHours?.toLong() ?: 24)

            if (LocalDateTime.now(tenantZone).isAfter(cancellationDeadline)) {
                throw IllegalStateException(
                    "Randevu başlangıcına ${settings?.cancellationPolicyHours ?: 24} " +
                    "saatten az kaldığında iptal yapılamaz"
                )
            }
        }

        appointment.status = AppointmentStatus.CANCELLED
        appointment.cancelledAt = Instant.now()
        appointment.cancellationReason = reason

        val saved = appointmentRepository.save(appointment)

        // İptal bildirimi gönder — DÜZELTME: Entity yerine DTO (lazy loading güvenli)
        val ctx = notificationService.toContext(saved)
        notificationService.sendAppointmentCancellation(ctx)

        return saved
    }

    /**
     * Randevu durumu güncelleme (admin)
     */
    @Transactional
    fun updateStatus(id: String, newStatus: AppointmentStatus, reason: String? = null): Appointment {
        val appointment = appointmentRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Randevu bulunamadı: $id") }

        validateStatusTransition(appointment.status, newStatus)

        appointment.status = newStatus

        if (newStatus == AppointmentStatus.CANCELLED) {
            appointment.cancelledAt = Instant.now()
            appointment.cancellationReason = reason
        }

        // NO_SHOW durumunda müşteri sayacını artır + kara liste kontrolü
        if (newStatus == AppointmentStatus.NO_SHOW) {
            val tenantId = TenantContext.getTenantId()
            appointment.client?.let { client ->
                client.noShowCount++
                if (client.noShowCount >= NO_SHOW_BLACKLIST_THRESHOLD) {
                    client.isBlacklisted = true
                    client.blacklistedAt = Instant.now()
                    client.blacklistReason = "${client.noShowCount} kez randevuya gelmedi"
                    logger.warn("[tenant={}] Müşteri kara listeye alındı: {} (no-show: {})",
                        tenantId, client.email, client.noShowCount)
                    // Kara liste bildirimi
                    val ctx = notificationService.toBlacklistContext(client)
                    notificationService.sendClientBlacklistedNotification(ctx, client.noShowCount)
                }
                userRepository.save(client)
            }
            // No-show bildirimi (TENANT_ADMIN'a)
            notificationService.sendNoShowNotification(notificationService.toContext(appointment))
        }

        return appointmentRepository.save(appointment)
    }

    /**
     * Tekrarlayan randevu oluşturma
     *
     * DÜZELTMELER:
     * - recurrenceRule doğrulaması döngü ÖNCESİNDE yapılır (kısmi oluşturma önlenir)
     * - Tüm exception'lar yakalanır (sadece conflict değil, çalışma saati vs. de)
     * - recurringGroupId ve recurrenceRule, appointment oluşturulmadan ÖNCE set edilir (ekstra save yok)
     *
     * NOT: createAppointment() aynı sınıftan çağrılır (self-invocation).
     * Spring AOP proxy bypass edilir → createAppointment'ın kendi @Transactional'ı çalışmaz.
     * Bu fonksiyonun @Transactional'ı tüm işlemi kapsar. Eğer bağımsız commit gerekirse
     * TransactionTemplate veya ayrı bir bean kullanılmalıdır.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun createRecurringAppointments(
        request: CreateAppointmentRequest,
        recurrenceRule: String,   // "WEEKLY", "BIWEEKLY", "MONTHLY"
        count: Int                // Kaç tekrar
    ): List<Appointment> {
        // ── ① Validasyon (döngü öncesi!) ──
        val validRules = setOf("WEEKLY", "BIWEEKLY", "MONTHLY")
        require(recurrenceRule in validRules) {
            "Geçersiz tekrar kuralı: $recurrenceRule. Geçerli: $validRules"
        }
        require(count in 1..52) {
            "Tekrar sayısı 1-52 arasında olmalıdır"
        }

        val groupId = UUID.randomUUID().toString()
        val appointments = mutableListOf<Appointment>()
        val skippedDates = mutableListOf<LocalDate>()

        var currentDate = request.date
        for (i in 0 until count) {
            val singleRequest = request.copy(date = currentDate)
            try {
                val appointment = createAppointment(singleRequest).apply {
                    this.recurringGroupId = groupId
                    this.recurrenceRule = recurrenceRule
                }
                // recurringGroupId zaten set edildi, save createAppointment içinde yapıldı
                appointments.add(appointment)
            } catch (e: Exception) {
                // Tüm hataları yakala (conflict, çalışma saati dışı, bloklanmış slot vs.)
                logger.warn("Tekrarlayan randevu atlandı: {} tarihinde — {}", currentDate, e.message)
                skippedDates.add(currentDate)
            }

            currentDate = when (recurrenceRule) {
                "WEEKLY" -> currentDate.plusWeeks(1)
                "BIWEEKLY" -> currentDate.plusWeeks(2)
                "MONTHLY" -> currentDate.plusMonths(1)
                else -> error("unreachable")  // Yukarıda validate edildi
            }
        }

        if (skippedDates.isNotEmpty()) {
            logger.info("Tekrarlayan randevu: {} başarılı, {} atlandı (tarihler: {})",
                appointments.size, skippedDates.size, skippedDates)
        }

        return appointments
    }

    private fun validateStatusTransition(current: AppointmentStatus, next: AppointmentStatus) {
        val allowedTransitions = mapOf(
            AppointmentStatus.PENDING to setOf(
                AppointmentStatus.CONFIRMED,
                AppointmentStatus.CANCELLED
            ),
            AppointmentStatus.CONFIRMED to setOf(
                AppointmentStatus.IN_PROGRESS,
                AppointmentStatus.CANCELLED,
                AppointmentStatus.NO_SHOW
            ),
            AppointmentStatus.IN_PROGRESS to setOf(
                AppointmentStatus.COMPLETED
            )
            // COMPLETED, CANCELLED, NO_SHOW → geçiş yapılamaz (son durum)
        )

        val allowed = allowedTransitions[current] ?: emptySet()
        if (next !in allowed) {
            throw IllegalStateException(
                "'$current' durumundan '$next' durumuna geçiş yapılamaz"
            )
        }
    }
}
```

### 5.3 Repository — Pessimistic Lock Query

```kotlin
@Repository
interface AppointmentRepository : JpaRepository<Appointment, String> {

    /**
     * Çakışan randevuları pessimistic lock ile sorgula.
     * SELECT ... FOR UPDATE ile satır seviyesinde kilit alır.
     * Aynı anda gelen ikinci istek, ilk transaction bitene kadar bekler.
     *
     * DÜZELTMELER:
     * - Enum karşılaştırması parametre ile (string literal JPQL'de hatalı)
     * - staffId NULL durumu: Belirli bir staff'ın randevuları kontrol edilir.
     *   "Herhangi bir personel" senaryosu burada DEĞİL, AvailabilityService'te ele alınır.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.tenantId = :tenantId
        AND a.date = :date
        AND a.status NOT IN (:excludeStatuses)
        AND a.staff.id = :staffId
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    fun findConflictingAppointmentsWithLock(
        @Param("tenantId") tenantId: String,
        @Param("staffId") staffId: String,
        @Param("date") date: LocalDate,
        @Param("startTime") startTime: LocalTime,
        @Param("endTime") endTime: LocalTime,
        @Param("excludeStatuses") excludeStatuses: List<AppointmentStatus> =
            listOf(AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)
    ): List<Appointment>

    /**
     * Belirli bir tarih aralığındaki randevuları getir
     */
    fun findByTenantIdAndDateBetweenAndStatusNot(
        tenantId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        excludeStatus: AppointmentStatus
    ): List<Appointment>

    /**
     * Personelin belirli gündeki randevuları
     */
    fun findByTenantIdAndStaffIdAndDateOrderByStartTime(
        tenantId: String,
        staffId: String,
        date: LocalDate
    ): List<Appointment>

    // ── Ek metodlar (plan limiti, hatırlatıcı, review) ──

    fun countByTenantIdAndCreatedAtAfter(tenantId: String, after: Instant): Long

    @Query("""
        SELECT a FROM Appointment a
        WHERE FUNCTION('TIMESTAMP', a.date, a.startTime) <= FUNCTION('TIMESTAMP', :date, :time)
        AND a.status = :status
        AND (:r24 IS NULL OR a.reminder24hSent = :r24)
        AND (:r1 IS NULL OR a.reminder1hSent = :r1)
    """)
    /* DÜZELTME K15: Tarih+saat birleşik karşılaştırma. Eski sorgu sadece aynı gündeki
       randevuları buluyordu. Yeni sorgu TIMESTAMP fonksiyonu ile date+startTime'ı tek
       datetime olarak karşılaştırır — gece yarısını geçen hesaplamalar doğru çalışır. */
    fun findUpcomingNotReminded(
        @Param("date") date: LocalDate,
        @Param("time") time: LocalTime,
        @Param("status") status: AppointmentStatus = AppointmentStatus.CONFIRMED,
        @Param("r24") reminder24hSent: Boolean? = null,
        @Param("r1") reminder1hSent: Boolean? = null
    ): List<Appointment>

    @Query("""
        SELECT a FROM Appointment a
        WHERE a.status = :status AND a.updatedAt >= :since
        AND NOT EXISTS (SELECT r FROM Review r WHERE r.appointment.id = a.id)
    """)
    fun findCompletedWithoutReview(
        @Param("since") since: Instant,
        @Param("status") status: AppointmentStatus = AppointmentStatus.COMPLETED
    ): List<Appointment>

    /**
     * Otomatik NO_SHOW tespiti için:
     * Randevu bitiş saati + 1 saat geçmiş ve hala CONFIRMED olan randevuları bul.
     */
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.tenantId = :tenantId
        AND a.status = 'CONFIRMED'
        AND (a.date < :cutoffDate OR (a.date = :cutoffDate AND a.endTime <= :cutoffTime))
    """)
    fun findConfirmedBeforeDateTime(
        @Param("tenantId") tenantId: String,
        @Param("cutoffDate") cutoffDate: LocalDate,
        @Param("cutoffTime") cutoffTime: LocalTime
    ): List<Appointment>

    /** DÜZELTME K10: GDPR veri dışa aktarma için — GdprService.exportUserData() tarafından kullanılır */
    fun findByClientEmail(email: String): List<Appointment>

    /** DÜZELTME F4: Misafir → kayıtlı müşteri eşleştirme — clientId olmayan randevuları bul */
    fun findByClientEmailAndClientIdIsNull(email: String): List<Appointment>

    /** DÜZELTME K11: GDPR anonimleştirme — clientName → "Anonim", clientEmail → "", clientPhone → "" */
    @Modifying
    @Query("""
        UPDATE Appointment a SET a.clientName = 'Anonim', a.clientEmail = '', a.clientPhone = ''
        WHERE a.clientEmail = :email
    """)
    fun anonymizeByClientEmail(@Param("email") email: String)
}
```

### 5.3.1 Diğer Repository Interface'leri

Kod genelinde çağrılan tüm Spring Data JPA repository metodları. Derived query method convention'ı ile otomatik SQL üretilir:

```kotlin
@Repository
interface SubscriptionRepository : JpaRepository<Subscription, String> {
    fun findByTenantIdAndStatus(tenantId: String, status: SubscriptionStatus): Subscription?
}

@Repository
interface UserRepository : JpaRepository<User, String> {
    fun findByEmailAndTenantId(email: String, tenantId: String): User?
    fun findByTenantIdAndRoleInAndIsActiveTrue(tenantId: String, roles: List<Role>): List<User>
    fun countByTenantIdAndRole(tenantId: String, role: Role): Long
}

// DÜZELTME: AppointmentRepository ek metodları 5.3'e taşındı (duplicate kaldırıldı)

@Repository
interface ContactMessageRepository : JpaRepository<ContactMessage, String> {
    fun deleteByIsReadTrueAndCreatedAtBefore(cutoff: Instant)  // DÜZELTME: LocalDateTime → Instant (entity field tipi)
    fun deleteByEmail(email: String)                           // DÜZELTME: GdprService tarafından kullanılır
}

@Repository
interface SiteSettingsRepository : JpaRepository<SiteSettings, String> {
    fun findByTenantId(tenantId: String): SiteSettings?
}

@Repository
interface TenantRepository : JpaRepository<Tenant, String> {
    fun findBySlugAndIsActiveTrue(slug: String): Tenant?
    fun findByCustomDomainAndIsActiveTrue(domain: String): Tenant?
    fun findAllByIsActiveTrue(): List<Tenant>
    @Query("SELECT t.customDomain FROM Tenant t WHERE t.customDomain IS NOT NULL AND t.isActive = true")
    fun findAllCustomDomains(): List<String>
    fun countByIsActiveTrue(): Long
}

@Repository
interface WorkingHoursRepository : JpaRepository<WorkingHours, String> {
    fun findByTenantIdAndStaffIdAndDayOfWeek(
        tenantId: String, staffId: String?, dayOfWeek: DayOfWeek  // DÜZELTME: staffId nullable (business-level saatler)
    ): WorkingHours?
}

@Repository
interface BlockedTimeSlotRepository : JpaRepository<BlockedTimeSlot, String> {
    fun findByTenantIdAndStaffIdAndDate(
        tenantId: String, staffId: String?, date: LocalDate   // DÜZELTME: staffId nullable
    ): List<BlockedTimeSlot>
}

// Pivot tablo repository — JpaRepository.save() ve findAll() yeterli
@Repository
interface AppointmentServiceRepository : JpaRepository<AppointmentService, String>

// Hizmet repository — JpaRepository.findAllById() miras alınır
@Repository
interface ServiceRepository : JpaRepository<Service, String>

// Bildirim template repository
@Repository
interface NotificationTemplateRepository : JpaRepository<NotificationTemplate, String> {
    fun findByTenantIdAndType(tenantId: String, type: NotificationType): NotificationTemplate?
}

// Bildirim log repository — JpaRepository.save() miras alınır
@Repository
interface NotificationLogRepository : JpaRepository<NotificationLog, String>

// DÜZELTME K9/AA-E2: ReviewRepository tam tanımı — GdprService, DashboardService tarafından kullanılır
@Repository
interface ReviewRepository : JpaRepository<Review, String> {
    fun findByUserId(userId: String): List<Review>
    fun findByServiceId(serviceId: String): List<Review>
    fun findByStaffId(staffId: String): List<Review>
    fun findByTenantIdAndIsApprovedTrue(tenantId: String, pageable: Pageable): Page<Review>
    fun countByTenantId(tenantId: String): Long
    fun averageRatingByTenantId(tenantId: String): Double?

    /** GDPR anonimleştirme — comment → "[Silindi]", rating korunur (istatistiksel veri) */
    @Modifying
    @Query("UPDATE Review r SET r.comment = '[Silindi]' WHERE r.client.id = :userId")
    fun anonymizeByUserId(@Param("userId") userId: String)
}

// DÜZELTME K11/AA-E3: RefreshTokenRepository tam tanımı — AuthService, GdprService tarafından kullanılır
@Repository
interface RefreshTokenRepository : JpaRepository<RefreshToken, String> {
    fun findByIdAndIsRevokedFalse(id: String): RefreshToken?
    fun findByFamilyAndIsRevokedFalse(family: String): List<RefreshToken>

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.isRevoked = true WHERE rt.family = :family")
    fun revokeFamily(@Param("family") family: String)

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.isRevoked = true WHERE rt.userId = :userId")
    fun revokeAllByUserId(@Param("userId") userId: String)

    /** DÜZELTME K11: GDPR silme — kullanıcının tüm refresh token'larını sil */
    fun deleteByUserId(userId: String)

    /** Süresi dolmuş token'ları temizle (scheduled job) */
    fun deleteByExpiresAtBefore(cutoff: Instant)
}

// DÜZELTME AA-E12: ServiceCategoryRepository — ServiceCategory CRUD işlemleri
@Repository
interface ServiceCategoryRepository : JpaRepository<ServiceCategory, String> {
    fun findByTenantIdOrderBySortOrderAsc(tenantId: String): List<ServiceCategory>
}
```

### 5.4 AvailabilityService — Müsait Slot Hesaplama

```kotlin
@Service
class AvailabilityService(
    private val workingHoursRepository: WorkingHoursRepository,
    private val appointmentRepository: AppointmentRepository,
    private val blockedTimeSlotRepository: BlockedTimeSlotRepository,
    private val userRepository: UserRepository               // DÜZELTME: findAvailableStaff() için gerekli
) {
    /**
     * Belirli bir gün için müsait zaman dilimlerini hesapla
     *
     * Algoritma:
     * 1. Çalışma saatlerini al → tüm slotları oluştur
     * 2. Mevcut randevuları çıkar
     * 3. Bloklanmış zamanları çıkar
     * 4. Kalan slotları döndür
     */
    fun getAvailableSlots(
        tenantId: String,
        staffId: String?,
        date: LocalDate,
        durationMinutes: Int,
        slotIntervalMinutes: Int = 30    // 30 dakikalık aralıklarla slot
    ): List<TimeSlotResponse> {

        val dayOfWeek = date.dayOfWeek

        // 1. Çalışma saatleri
        val workingHours = workingHoursRepository.findByTenantIdAndStaffIdAndDayOfWeek(
            tenantId, staffId, dayOfWeek
        ) ?: return emptyList()

        if (!workingHours.isWorkingDay) return emptyList()

        // 2. Tüm olası slotları oluştur
        val allSlots = generateTimeSlots(
            start = workingHours.startTime,
            end = workingHours.endTime,
            duration = durationMinutes,
            interval = slotIntervalMinutes,
            breakStart = workingHours.breakStartTime,
            breakEnd = workingHours.breakEndTime
        )

        // 3. Mevcut randevuları al
        // DÜZELTME: staffId null durumunda boş string DEĞİL, null-safe sorgu kullanılmalı
        val existingAppointments = if (staffId != null) {
            appointmentRepository.findByTenantIdAndStaffIdAndDateOrderByStartTime(
                tenantId, staffId, date
            ).filter { it.status !in listOf(AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW) }
        } else {
            // DÜZELTME: staffId null → tüm randevuları getir, slot hesaplamasında "en az 1 staff müsait" kontrolü yapılır
            appointmentRepository.findByTenantIdAndDateBetweenAndStatusNot(
                tenantId, date, date, AppointmentStatus.CANCELLED
            )
        }

        // 4. Bloklanmış zamanları al
        val blockedSlots = blockedTimeSlotRepository
            .findByTenantIdAndStaffIdAndDate(tenantId, staffId, date)

        // 5. Çakışanları çıkar
        return allSlots.map { slot ->
            val isBooked = existingAppointments.any { appt ->
                slot.startTime < appt.endTime && slot.endTime > appt.startTime
            }
            val isBlocked = blockedSlots.any { blocked ->
                slot.startTime < blocked.endTime && slot.endTime > blocked.startTime
            }
            TimeSlotResponse(
                startTime = slot.startTime,
                endTime = slot.endTime,
                isAvailable = !isBooked && !isBlocked
            )
        }
    }

    private fun generateTimeSlots(
        start: LocalTime,
        end: LocalTime,
        duration: Int,
        interval: Int,
        breakStart: LocalTime?,
        breakEnd: LocalTime?
    ): List<TimeSlot> {
        val slots = mutableListOf<TimeSlot>()
        var current = start

        while (current.plusMinutes(duration.toLong()) <= end) {
            val slotEnd = current.plusMinutes(duration.toLong())

            // Öğle arası kontrolü
            val isDuringBreak = breakStart != null && breakEnd != null &&
                current < breakEnd && slotEnd > breakStart

            if (!isDuringBreak) {
                slots.add(TimeSlot(startTime = current, endTime = slotEnd))
            }

            current = current.plusMinutes(interval.toLong())
        }

        return slots
    }

    fun isWithinWorkingHours(
        tenantId: String,
        staffId: String?,
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime
    ): Boolean {
        val wh = workingHoursRepository.findByTenantIdAndStaffIdAndDayOfWeek(
            tenantId, staffId, date.dayOfWeek
        ) ?: return false

        if (!wh.isWorkingDay) return false
        if (startTime < wh.startTime || endTime > wh.endTime) return false

        // DÜZELTME: Öğle arası kontrolü — randevu break time'a çakışıyor mu?
        if (wh.breakStartTime != null && wh.breakEndTime != null) {
            if (startTime < wh.breakEndTime!! && endTime > wh.breakStartTime!!) {
                return false  // Randevu öğle arasına denk geliyor
            }
        }

        return true
    }

    fun isTimeSlotBlocked(
        tenantId: String,
        staffId: String?,
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime
    ): Boolean {
        val blocked = blockedTimeSlotRepository.findByTenantIdAndStaffIdAndDate(
            tenantId, staffId, date
        )
        return blocked.any { it.startTime < endTime && it.endTime > startTime }
    }

    /**
     * "Herhangi bir personel" senaryosu: Verilen zaman diliminde müsait olan
     * ilk personelin ID'sini döndürür. Hiçbiri müsait değilse null.
     */
    fun findAvailableStaff(
        tenantId: String,
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime
    ): String? {
        // Tüm aktif personeli al
        val staffList = userRepository.findByTenantIdAndRoleInAndIsActiveTrue(
            tenantId, listOf(Role.STAFF, Role.TENANT_ADMIN)
        )

        for (staff in staffList) {
            val staffId = staff.id ?: continue

            // 1. Çalışma saatleri uygun mu?
            if (!isWithinWorkingHours(tenantId, staffId, date, startTime, endTime)) continue

            // 2. Bloklanmış mı?
            if (isTimeSlotBlocked(tenantId, staffId, date, startTime, endTime)) continue

            // 3. Çakışan randevu var mı?
            val conflicts = appointmentRepository.findConflictingAppointmentsWithLock(
                tenantId = tenantId,
                staffId = staffId,
                date = date,
                startTime = startTime,
                endTime = endTime
            )
            if (conflicts.isEmpty()) return staffId  // Bu personel müsait!
        }

        return null  // Hiçbir personel müsait değil
    }
}
```

---

## 6. API Endpoint Tasarımı

### 6.1 Public API (Auth gerektirmez)

Tenant'ın açık sitesinden gelen istekler. Subdomain'den tenant çözümlenir.

```
GET    /api/public/services                  # Aktif hizmetleri listele
GET    /api/public/services/{slug}           # Hizmet detayı
GET    /api/public/products                  # Aktif ürünleri listele
GET    /api/public/products/{slug}           # Ürün detayı
GET    /api/public/blog                      # Yayınlanmış blog yazıları
GET    /api/public/blog/{slug}               # Blog yazısı detayı
GET    /api/public/gallery                   # Aktif galeri öğeleri
GET    /api/public/staff                     # Aktif personel listesi (ad, fotoğraf, uzmanlık)
       ?serviceId=xxx (opsiyonel)            # Bu hizmeti veren personeller
GET    /api/public/availability              # Müsait randevu slotları
       ?date=2026-02-15
       &serviceId=xxx
       &staffId=xxx (opsiyonel)              # null ise tüm personelin slotları
GET    /api/public/reviews                   # Onaylı değerlendirmeler (müşteri yorumları)
       ?staffId=xxx (opsiyonel)
POST   /api/public/appointments              # Randevu oluştur (misafir veya CLIENT)
POST   /api/public/contact                   # İletişim formu gönder
GET    /api/public/settings                  # Site ayarları (isim, adres, vb.)
```

### 6.2 Auth API

```
POST   /api/auth/login                       # Email + şifre → JWT token pair
POST   /api/auth/register                    # Yeni müşteri kaydı
POST   /api/auth/refresh                     # Refresh token → yeni access token
POST   /api/auth/forgot-password             # Şifre sıfırlama e-postası gönder
POST   /api/auth/reset-password              # Token ile şifre sıfırla
GET    /api/auth/me                          # Mevcut kullanıcı bilgisi
```

### 6.3 Admin API (TENANT_ADMIN + STAFF kısıtlı login)

```
# Dashboard
GET    /api/admin/dashboard/stats            # İstatistikler (randevu, hasta, gelir)

# Hizmetler
GET    /api/admin/services                   # Tüm hizmetler (aktif + pasif)
POST   /api/admin/services                   # Yeni hizmet oluştur
GET    /api/admin/services/{id}              # Hizmet detayı
PUT    /api/admin/services/{id}              # Hizmet güncelle
DELETE /api/admin/services/{id}              # Hizmet sil (soft delete)
PATCH  /api/admin/services/{id}/sort         # Sıralama güncelle

# Ürünler
GET    /api/admin/products
GET    /api/admin/products/{id}
POST   /api/admin/products
PUT    /api/admin/products/{id}
DELETE /api/admin/products/{id}

# Blog
GET    /api/admin/blog
GET    /api/admin/blog/{id}
POST   /api/admin/blog
PUT    /api/admin/blog/{id}
DELETE /api/admin/blog/{id}
PATCH  /api/admin/blog/{id}/publish          # Yayınla/taslak yap

# Galeri
GET    /api/admin/gallery
GET    /api/admin/gallery/{id}
POST   /api/admin/gallery
PUT    /api/admin/gallery/{id}
DELETE /api/admin/gallery/{id}

# Değerlendirmeler
GET    /api/admin/reviews                    # Değerlendirme listesi
PATCH  /api/admin/reviews/{id}/approve       # Onayla/reddet
DELETE /api/admin/reviews/{id}               # Değerlendirme sil

# Randevular
GET    /api/admin/appointments               # Filtreli liste
       ?status=PENDING
       &date=2026-02-15
       &staffId=xxx
POST   /api/admin/appointments               # Admin'den randevu oluştur
PUT    /api/admin/appointments/{id}          # Randevu güncelle
PATCH  /api/admin/appointments/{id}/status   # Durum değiştir (confirm, cancel, complete)

# Hastalar / Müşteriler (core — tüm planlarda)
GET    /api/admin/patients                   # Hasta/müşteri listesi
GET    /api/admin/patients/{id}              # Hasta detayı + randevu geçmişi
PUT    /api/admin/patients/{id}              # Hasta bilgisi güncelle
GET    /api/admin/patients/blacklisted       # Kara listedeki müşteriler
PATCH  /api/admin/patients/{id}/unblock      # Kara listeden çıkar (noShowCount sıfırla)

# Hasta Kayıtları (@RequiresModule(PATIENT_RECORDS) — add-on modül)
GET    /api/admin/patients/{id}/record       # Yapılandırılmış hasta kaydı (JSON)
PUT    /api/admin/patients/{id}/record       # Hasta kaydı güncelle
POST   /api/admin/patients/{id}/treatments   # Yeni tedavi kaydı ekle
GET    /api/admin/patients/{id}/treatments   # Tedavi geçmişi listesi
PUT    /api/admin/treatments/{id}            # Tedavi kaydı güncelle
DELETE /api/admin/treatments/{id}            # Tedavi kaydı sil

# İletişim Mesajları
GET    /api/admin/messages                   # Mesaj listesi
GET    /api/admin/messages/{id}              # Mesaj detayı
GET    /api/admin/messages/unread-count      # Okunmamış mesaj sayısı (inbox badge)
PATCH  /api/admin/messages/{id}/read         # Okundu işaretle
DELETE /api/admin/messages/{id}              # Mesaj sil

# Bildirim Yönetimi
GET    /api/admin/notifications              # Bildirim geçmişi
GET    /api/admin/notifications/templates    # Bildirim şablonları
PUT    /api/admin/notifications/templates/{id}  # Şablon güncelle

# Personel Yönetimi
GET    /api/admin/staff                      # Personel listesi
POST   /api/admin/staff                      # Yeni personel ekle
PUT    /api/admin/staff/{id}                 # Personel güncelle
DELETE /api/admin/staff/{id}                 # Personel çıkar

# Çalışma Saatleri
GET    /api/admin/working-hours              # Genel çalışma saatleri
PUT    /api/admin/working-hours              # Çalışma saatlerini güncelle
GET    /api/admin/working-hours/staff/{id}   # Personel çalışma saatleri
PUT    /api/admin/working-hours/staff/{id}   # Personel saatlerini güncelle

# Bloklanmış Zamanlar
GET    /api/admin/blocked-slots              # Bloklanmış zaman dilimleri
POST   /api/admin/blocked-slots              # Zaman dilimi blokla
DELETE /api/admin/blocked-slots/{id}         # Bloklamayı kaldır

# Abonelik & Modül Yönetimi
GET    /api/admin/billing/current-plan       # Mevcut plan + kullanım istatistikleri + aktif modüller
GET    /api/admin/billing/modules            # Aktif modül listesi
POST   /api/admin/billing/modules/{key}      # Modül etkinleştir (add-on satın al)
DELETE /api/admin/billing/modules/{key}      # Modül devre dışı bırak
GET    /api/admin/billing/invoices           # Fatura listesi
POST   /api/admin/billing/subscribe          # Plan seçimi + ödeme başlatma
POST   /api/admin/billing/upgrade            # Plan yükseltme
POST   /api/admin/billing/cancel             # Abonelik iptali

# Site Ayarları
GET    /api/admin/settings                   # Site ayarlarını getir
PUT    /api/admin/settings                   # Site ayarlarını güncelle

# SEO Yönetimi
GET    /api/admin/seo/pages                  # Tüm sayfaların SEO bilgileri
PUT    /api/admin/seo/pages/{type}/{id}      # Sayfa SEO bilgisi güncelle

# Dosya Yükleme
POST   /api/admin/upload                     # Görsel yükle (multipart)
DELETE /api/admin/upload/{fileId}            # Görseli sil
```

### 6.4 Platform Admin API (PLATFORM_ADMIN only)

```
# Tenant Yönetimi
GET    /api/platform/tenants                 # Tüm tenant'ları listele
POST   /api/platform/tenants                 # Yeni tenant oluştur (onboarding)
GET    /api/platform/tenants/{id}            # Tenant detayı
PUT    /api/platform/tenants/{id}            # Tenant güncelle
PATCH  /api/platform/tenants/{id}/activate   # Tenant aktif/pasif
PATCH  /api/platform/tenants/{id}/plan       # Plan değiştir

# Platform İstatistikler
GET    /api/platform/stats                   # Genel platform istatistikleri
GET    /api/platform/stats/tenants           # Tenant bazlı istatistikler
```

### 6.5 Standart Response Format

```kotlin
// Başarılı yanıt
data class ApiResponse<T>(
    val success: Boolean = true,
    val data: T? = null,
    val message: String? = null,
    val timestamp: Instant = Instant.now()       // UTC (timezone bağımsız)
)

// Sayfalı yanıt
data class PagedResponse<T>(
    val success: Boolean = true,
    val data: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val message: String? = null,
    val timestamp: Instant = Instant.now()       // ApiResponse ile tutarlı
)

// Hata yanıtı
data class ErrorResponse(
    val success: Boolean = false,
    val error: String,
    val code: ErrorCode,                          // Enum (aşağıda tanımlı)
    val details: Map<String, String>? = null,
    val timestamp: Instant = Instant.now()
)

// Hata kodları enum'u — Tüm API'da tutarlı hata kodları
enum class ErrorCode {
    // Auth
    INVALID_CREDENTIALS,          // Geçersiz e-posta veya şifre
    ACCOUNT_LOCKED,               // Hesap kilitli
    TOKEN_EXPIRED,                // Token süresi dolmuş
    TOKEN_INVALID,                // Geçersiz token

    // Tenant
    TENANT_NOT_FOUND,             // Tenant bulunamadı
    CROSS_TENANT_ACCESS,          // Cross-tenant erişim girişimi

    // Resource
    RESOURCE_NOT_FOUND,           // Kaynak bulunamadı
    DUPLICATE_RESOURCE,           // Aynı kaynak zaten var

    // Appointment
    APPOINTMENT_CONFLICT,         // Zaman çakışması
    APPOINTMENT_INVALID_STATUS,   // Geçersiz durum geçişi
    APPOINTMENT_PAST_DATE,        // Geçmiş tarihe randevu
    NO_AVAILABLE_STAFF,           // Müsait personel yok

    // Validation
    VALIDATION_ERROR,             // Bean validation hatası
    INVALID_FILE_TYPE,            // Desteklenmeyen dosya türü
    FILE_TOO_LARGE,               // Dosya boyutu aşıldı

    // Rate Limiting
    RATE_LIMIT_EXCEEDED,          // Çok fazla istek

    // Plan
    PLAN_LIMIT_EXCEEDED,          // DÜZELTME T2: Plan limiti aşıldı (403 Forbidden)
    FORBIDDEN,                    // DÜZELTME AA-O9: Genel erişim engeli

    // Blacklist
    CLIENT_BLACKLISTED,           // Müşteri kara listede (no-show limit aşıldı)

    // General
    INTERNAL_ERROR                // Beklenmeyen hata
}
```

### 6.6 Request/Response DTO Tanımları

```kotlin
// ── Request DTO'ları ── (Controller'a gelen istekler)

data class CreateAppointmentRequest(
    @field:NotNull val date: LocalDate,
    @field:NotNull val startTime: LocalTime,
    @field:NotBlank val clientName: String,
    @field:Email val clientEmail: String,
    val clientPhone: String = "",
    @field:NotEmpty val serviceIds: List<String>,  // Çoklu hizmet desteği
    val staffId: String? = null,                   // null = otomatik atama
    val notes: String? = null,
    val recurrenceRule: String? = null              // WEEKLY, BIWEEKLY, MONTHLY (opsiyonel)
)

data class CreateServiceRequest(
    @field:NotBlank val title: String,
    @field:NotBlank val slug: String,
    val categoryId: String? = null,
    val shortDescription: String = "",
    val description: String = "",
    @field:PositiveOrZero val price: BigDecimal = BigDecimal.ZERO,
    val currency: String = "TRY",
    @field:Min(5) @field:Max(480) val durationMinutes: Int = 30,
    @field:Min(0) val bufferMinutes: Int = 0,
    val image: String? = null,
    val benefits: List<String> = emptyList(),
    val processSteps: List<String> = emptyList(),
    val recovery: String? = null,
    val metaTitle: String? = null,
    val metaDescription: String? = null
)

// ── Response DTO'ları ── (API'dan dönen yanıtlar — entity doğrudan dönmez!)

data class ServiceResponse(
    val id: String,
    val slug: String,
    val title: String,
    val categoryName: String?,
    val shortDescription: String,
    val price: BigDecimal,
    val currency: String,
    val durationMinutes: Int,
    val image: String?,
    val isActive: Boolean
)

data class AppointmentResponse(
    val id: String,
    val clientName: String,
    val services: List<String>,      // Hizmet adları
    val staffName: String?,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val totalPrice: BigDecimal,
    val status: AppointmentStatus,
    val createdAt: Instant?
)

// ── Staff DTO'ları ── (STAFF kısıtlı login (read-only: kendi takvimi) — oluşturma/güncelleme TENANT_ADMIN tarafından)

data class CreateStaffRequest(
    @field:NotBlank val name: String,
    @field:Email val email: String,                 // İletişim amaçlı (login için değil!)
    val phone: String? = null,
    val image: String? = null,
    val title: String? = null                       // "Uzman Diyetisyen", "Dr.", "Kuaför"
)
// NOT: STAFF için TENANT_ADMIN şifre belirler (CreateStaffRequest'e password alanı eklenmeli).
// Service katmanında: user.passwordHash = encodedPassword, user.role = Role.STAFF

data class StaffPublicResponse(                     // GET /api/public/staff
    val id: String,
    val name: String,
    val image: String?,
    val title: String?                              // Uzmanlık alanı
)

// ── Hasta Kaydı DTO'ları ── (@RequiresModule(PATIENT_RECORDS))

data class CreatePatientRecordRequest(
    @field:NotBlank val clientId: String,
    val structuredData: Map<String, Any> = emptyMap(),  // Sektöre göre JSON
    val generalNotes: String? = null
)

data class UpdatePatientRecordRequest(
    val structuredData: Map<String, Any>? = null,
    val generalNotes: String? = null
)

data class PatientRecordResponse(
    val id: String,
    val clientId: String,
    val clientName: String,
    val structuredData: Map<String, Any>,
    val generalNotes: String?,
    val treatmentCount: Int,
    val createdAt: Instant?
)

data class CreateTreatmentRequest(
    @field:NotNull val treatmentDate: LocalDate,
    @field:NotBlank val title: String,
    val description: String? = null,
    val appointmentId: String? = null,              // İlişkili randevu (opsiyonel)
    val performedById: String? = null,              // Tedaviyi yapan personel
    val treatmentData: Map<String, Any> = emptyMap(),
    val attachments: List<String> = emptyList()     // Dosya URL'leri
)

data class TreatmentHistoryResponse(
    val id: String,
    val treatmentDate: LocalDate,
    val title: String,
    val description: String?,
    val performedByName: String?,
    val treatmentData: Map<String, Any>,
    val attachments: List<String>,
    val createdAt: Instant?
)

// ── Abonelik & Modül DTO'ları ──

data class SubscribeRequest(
    @field:NotNull val plan: SubscriptionPlan,
    val modules: Set<FeatureModule> = emptySet(),   // İstenen add-on modüller
    val billingPeriod: BillingPeriod = BillingPeriod.MONTHLY,
    val industryBundle: IndustryBundle? = null       // Sektör paketi ile hızlı başlangıç
)

data class SubscriptionResponse(
    val plan: SubscriptionPlan,
    val status: SubscriptionStatus,
    val modules: List<ModuleResponse>,
    val maxStaff: Int,
    val maxAppointmentsPerMonth: Int,
    val monthlyPrice: BigDecimal,
    val billingPeriod: BillingPeriod,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val autoRenew: Boolean
)

data class ModuleResponse(
    val moduleKey: FeatureModule,
    val isEnabled: Boolean,
    val monthlyPrice: BigDecimal
)

// ── Diğer Eksik DTO'lar ──

data class TimeSlotResponse(                        // GET /api/public/availability
    val startTime: LocalTime,
    val endTime: LocalTime,
    val isAvailable: Boolean
)

// Not: Her entity için ayrı Request/Response DTO oluşturulur.
// Entity doğrudan API'dan ASLA dönülmez (güvenlik: hassas alanlar sızar).
```

---

## 7. Güvenlik Mimarisi

### 7.1 JWT Token Yapısı + Server-Side Revocation

> **Login yapabilen roller:** PLATFORM_ADMIN, TENANT_ADMIN, STAFF, CLIENT (4 rol)
> **STAFF:** Kısıtlı login — read-only erişim (kendi takvimi ve randevuları). TENANT_ADMIN tarafından şifre belirlenir.

```
Access Token (1 saat ömür — tüm login yapabilen roller aynı):
{
  "sub": "user-uuid",
  "tenantId": "tenant-uuid",
  "tenantSlug": "salon1",
  "role": "TENANT_ADMIN",
  "email": "admin@salon1.com",
  "tokenFamily": "family-uuid",     // Refresh token theft detection
  "iat": 1707580800,
  "exp": 1707584400                  // +3600 saniye (1 saat)
}

Refresh Token (rol bazlı ömür, DB'de saklanır):
  - PLATFORM_ADMIN:  1 gün   (en hassas hesap — kısa ömür)
  - TENANT_ADMIN:   30 gün   (günlük işletme yönetimi)
  - CLIENT:         60 gün   (kullanıcı deneyimi — sık login istenmez)
  - STAFF:           7 gün   (kısıtlı erişim — read-only)
{
  "sub": "user-uuid",
  "type": "refresh",
  "jti": "unique-token-id",         // DB'deki refresh_tokens.id ile eşleşir
  "family": "family-uuid",          // Token ailesi (theft detection)
  "iat": 1707580800,
  "exp": 1710172800                  // Rol bazlı süre
}
```

**Token Revocation Mekanizması:**

```kotlin
// RefreshToken.kt — DB'de saklanan refresh token'lar
@Entity
@Table(
    name = "refresh_tokens",
    indexes = [
        Index(name = "idx_rt_user", columnList = "user_id"),             // Kullanıcının tüm token'ları
        Index(name = "idx_rt_family", columnList = "family"),            // Theft detection
        Index(name = "idx_rt_expires", columnList = "expires_at")        // Expired token temizliği
    ]
)
class RefreshToken(
    @Id
    val id: String,                          // JWT'deki "jti" claim
    val userId: String,
    val tenantId: String,
    val family: String,                       // Token ailesi
    val expiresAt: Instant,                  // UTC (timezone bağımsız)
    var isRevoked: Boolean = false,
    val createdAt: Instant = Instant.now()   // UTC
)
```

**Revocation senaryoları:**
- Kullanıcı şifre değiştirince → o kullanıcının TÜM refresh token'ları revoke
- Hesap deaktive edilince → TÜM token'ları revoke
- Refresh token kullanılınca → eski token revoke, yeni token oluştur (rotation)
- Token theft tespiti → aynı family'deki TÜM token'lar revoke (tüm cihazlardan çıkış)

### 7.2 Brute Force Koruması

```kotlin
// JwtConfig.kt — JWT token ayarları (application.yml'den yüklenir)
@ConfigurationProperties(prefix = "jwt")
data class JwtProperties(
    val secret: String,
    val accessTokenExpiration: Long,                         // 3600000 ms (1 saat)
    val refreshTokenExpiration: RefreshTokenExpiration
) {
    data class RefreshTokenExpiration(
        val platformAdmin: Long,                             // 86400000 ms (1 gün)
        val tenantAdmin: Long,                               // 2592000000 ms (30 gün)
        val client: Long                                     // 5184000000 ms (60 gün)
    )

    /**
     * Rol bazlı refresh token süresi döndürür.
     * STAFF dahil tüm roller için token süresi tanımlıdır.
     */
    fun getRefreshExpirationForRole(role: Role): Long = when (role) {
        Role.PLATFORM_ADMIN -> refreshTokenExpiration.platformAdmin
        Role.TENANT_ADMIN   -> refreshTokenExpiration.tenantAdmin
        Role.CLIENT         -> refreshTokenExpiration.client
        Role.STAFF          -> refreshTokenExpiration.staff
    }
}

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val settingsRepository: SiteSettingsRepository,
    private val jwtProperties: JwtProperties                  // Rol bazlı token süreleri
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AuthService::class.java)
        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCK_DURATION_MINUTES = 15L
        // Login yapabilen roller (STAFF dahil — kısıtlı erişim)
        val LOGINABLE_ROLES = setOf(Role.PLATFORM_ADMIN, Role.TENANT_ADMIN, Role.CLIENT, Role.STAFF)
    }

    @Transactional
    fun login(request: LoginRequest): TokenResponse {
        val tenantId = TenantContext.getTenantId()
        val user = userRepository.findByEmailAndTenantId(request.email, tenantId)
            ?: throw UnauthorizedException("Geçersiz e-posta veya şifre")

        // STAFF kısıtlı login — şifre kontrolü yapılır, read-only erişim verilir

        // Hesap kilitli mi kontrol et
        // KRİTİK: lockedUntil Instant (UTC) — timezone dönüşümü gereksiz
        val now = Instant.now()

        val lockedUntil = user.lockedUntil                    // DÜZELTME: Smart cast için local val
        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            val remainingMinutes = Duration.between(now, lockedUntil).toMinutes()
            throw AccountLockedException(
                "Hesabınız çok fazla başarısız giriş denemesi nedeniyle kilitlendi. " +
                "$remainingMinutes dakika sonra tekrar deneyin."
            )
        }

        // Şifre kontrolü
        // DÜZELTME: passwordHash nullable (STAFF için null) — NPE koruması
        val hash = user.passwordHash
        if (hash == null || !passwordEncoder.matches(request.password, hash)) {
            user.failedLoginAttempts++
            if (user.failedLoginAttempts >= MAX_FAILED_ATTEMPTS) {
                user.lockedUntil = now.plus(LOCK_DURATION_MINUTES, ChronoUnit.MINUTES)
                logger.warn("[tenant={}] Hesap kilitlendi: {} ({} başarısız deneme)",
                    tenantId, user.email, user.failedLoginAttempts)
            }
            userRepository.save(user)
            throw UnauthorizedException("Geçersiz e-posta veya şifre")
        }

        // Başarılı giriş — sayaçları sıfırla
        user.failedLoginAttempts = 0
        user.lockedUntil = null
        userRepository.save(user)

        // Token pair oluştur (rol bazlı refresh token süresi)
        val family = UUID.randomUUID().toString()
        val refreshExpiration = jwtProperties.getRefreshExpirationForRole(user.role)
        return generateTokenPair(user, family, refreshExpiration)
    }
}
```

### 7.3 Spring Security Konfigürasyonu

```kotlin
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthenticationFilter,
    private val tenantFilter: TenantFilter
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // CSRF devre dışı: REST API, JWT token-based auth kullanır.
            // CSRF koruması session-based auth için gereklidir, stateless API'da gereksizdir.
            .csrf { it.disable() }
            .cors { }  // DÜZELTME: corsConfigSource @Bean olduğu için Spring IoC otomatik inject eder — doğrudan çağrı yapılmamalı
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .authorizeHttpRequests { auth ->
                auth
                    // Public endpoints
                    .requestMatchers("/api/public/**").permitAll()
                    .requestMatchers(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/auth/refresh",              // DÜZELTME: Refresh de permitAll olmalı!
                        "/api/auth/forgot-password",
                        "/api/auth/reset-password"
                    ).permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()  // DÜZELTME: Docker healthcheck + k8s probes
                    .requestMatchers("/actuator/prometheus").permitAll()                // DÜZELTME: Prometheus scraper erişimi
                    .requestMatchers("/api/webhooks/**").permitAll()  // Dış servis callback (iyzico vb.)
                    // Platform admin
                    .requestMatchers("/api/platform/**").hasAuthority("PLATFORM_ADMIN")    // DÜZELTME: hasRole() ROLE_ prefix ekler — authority ile eşleşmez
                    // Admin endpoints — sadece TENANT_ADMIN
                    .requestMatchers("/api/admin/**").hasAuthority("TENANT_ADMIN")
                    // DÜZELTME F3: Client endpoint'leri — sadece CLIENT rolü
                    .requestMatchers("/api/client/**").hasAuthority("CLIENT")
                    // DÜZELTME F9: Staff endpoint'leri — STAFF read-only erişim
                    .requestMatchers("/api/staff/**").hasAuthority("STAFF")
                    // Authenticated
                    .anyRequest().authenticated()
            }

        return http.build()
    }

    /**
     * CORS konfigürasyonu — Dinamik origin desteği.
     * Tüm tenant subdomain'leri + custom domain'ler desteklenir.
     */
    @Bean
    fun corsConfigSource(tenantRepository: TenantRepository): CorsConfigurationSource {
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", CorsConfiguration().apply {
                // Statik pattern'lar
                // DİKKAT (S2): Wildcard subdomain, CORS preflight aşamasında TenantFilter çalışmaz.
                // evil.app.com gibi kayıt dışı subdomain'ler de CORS geçer.
                // Üretimde: CorsConfigurationSource içinde tenant doğrulaması eklenmeli
                // veya allowedOriginPatterns yerine allowedOrigins (tam eşleşme) kullanılmalı.
                allowedOriginPatterns = mutableListOf(
                    "https://*.app.com",        // Tüm tenant subdomain'leri (subdomain spoofing riski — yukarıdaki nota bkz.)
                    "http://localhost:3000"      // Geliştirme
                )

                // DÜZELTME: Custom domain desteği
                // Tenant'ların kayıtlı custom domain'lerini de CORS origin olarak ekle
                val customDomains = tenantRepository.findAllCustomDomains()
                customDomains.forEach { domain ->
                    allowedOriginPatterns!!.add("https://$domain")
                }

                allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                allowedHeaders = listOf("*")
                allowCredentials = true
                maxAge = 3600
            })
        }
        // DÜZELTME AA-O6: Custom domain eklendiğinde CORS listesi yenilenmez.
        // Çözüm: @Scheduled(fixedRate = 300_000) job ile CorsConfigurationSource'u
        // periyodik olarak yeniden oluştur (5 dakikada bir). Alternatif: CachingCorsConfigurationSource
        // wrapper ile TTL bazlı lazy refresh.
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)
}
```

### 7.4 Modül Erişim Guard (ModuleGuardInterceptor)

Admin API endpoint'lerine modül bazlı erişim kontrolü. Tenant'ın modülü aktif değilse 403 döner.

```kotlin
/**
 * Custom annotation — Controller metotlarında modül erişim gereksinimini belirtir.
 * Örnek: @RequiresModule(FeatureModule.BLOG)
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresModule(val value: FeatureModule)

/**
 * Spring MVC Interceptor — Her admin API çağrısında modül erişim kontrolü yapar.
 * @RequiresModule annotation'ı olan endpoint'lerde tenant'ın ilgili modüle erişimi kontrol edilir.
 * Modül kapalıysa → 403 Forbidden (PlanLimitExceededException)
 */
@Component
class ModuleGuardInterceptor(
    private val moduleAccessService: ModuleAccessService
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        if (handler !is HandlerMethod) return true

        // @RequiresModule annotation'ı var mı?
        val annotation = handler.getMethodAnnotation(RequiresModule::class.java)
            ?: handler.beanType.getAnnotation(RequiresModule::class.java)
            ?: return true  // Annotation yoksa → modül kontrolü gerekmez

        val tenantId = TenantContext.getTenantId()
        moduleAccessService.requireAccess(tenantId, annotation.value)

        return true
    }
}

// WebConfig'de interceptor kaydı:
@Configuration
class WebConfig(private val moduleGuardInterceptor: ModuleGuardInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(moduleGuardInterceptor)
            .addPathPatterns("/api/admin/**", "/api/public/**", "/api/client/**", "/api/staff/**")
            // DÜZELTME F6: Public, client ve staff endpoint'lerinde de modül kontrolü
    }
}
```

**Controller'larda kullanım örnekleri:**

```kotlin
// Blog Controller — BLOG modülü gerektirir
@RestController
@RequestMapping("/api/admin/blog")
@RequiresModule(FeatureModule.BLOG)                 // Tüm endpoint'ler BLOG modülü gerektirir
class BlogAdminController(private val blogService: BlogService) {
    @GetMapping fun list(pageable: Pageable) = blogService.findAll(pageable)
    @PostMapping fun create(@Valid @RequestBody req: CreateBlogPostRequest) = blogService.create(req)
    // ...
}

// Product Controller — PRODUCTS modülü gerektirir
@RestController
@RequestMapping("/api/admin/products")
@RequiresModule(FeatureModule.PRODUCTS)
class ProductAdminController(private val productService: ProductService) { /* ... */ }

// Gallery Controller — GALLERY modülü gerektirir
@RestController
@RequestMapping("/api/admin/gallery")
@RequiresModule(FeatureModule.GALLERY)
class GalleryAdminController(private val galleryService: GalleryService) { /* ... */ }

// Patient Records Controller — PATIENT_RECORDS modülü gerektirir
@RestController
@RequestMapping("/api/admin/patients")
@RequiresModule(FeatureModule.PATIENT_RECORDS)
class PatientRecordController(private val patientService: PatientRecordService) { /* ... */ }

// Review Controller — REVIEWS modülü gerektirir
@RestController
@RequestMapping("/api/admin/reviews")
@RequiresModule(FeatureModule.REVIEWS)
class ReviewAdminController(private val reviewService: ReviewService) { /* ... */ }

// Randevu, Dashboard, Settings gibi core controller'lar @RequiresModule KULLANMAZ
// Core modüller her pakette dahildir
```

### 7.5 Rate Limiting

```kotlin
// RateLimitConfig.kt — Bucket4j ile rate limiting
@Configuration
class RateLimitConfig {
    @Bean
    fun rateLimitFilter(): FilterRegistrationBean<RateLimitFilter> {
        val registration = FilterRegistrationBean<RateLimitFilter>()
        registration.filter = RateLimitFilter()
        registration.addUrlPatterns("/api/*")
        return registration
    }
}

// RateLimitFilter.kt — Endpoint bazlı rate limiting
class RateLimitFilter : OncePerRequestFilter() {

    // IP bazlı bucket'lar (ConcurrentHashMap + Caffeine eviction ile)
    private val ipBuckets: Cache<String, Bucket> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(Duration.ofMinutes(5))
        .build()

    // DÜZELTME S3: userId bazlı bucket — authenticated endpoint'lerde IP yerine userId kullan
    // Proxy/VPN ile farklı IP'lerden sınırsız istek atılmasını engeller
    private val userBuckets: Cache<String, Bucket> = Caffeine.newBuilder()
        .maximumSize(50_000)
        .expireAfterAccess(Duration.ofMinutes(5))
        .build()
    // Kullanım: val userId = SecurityContextHolder.getContext().authentication?.name
    //           val bucket = if (userId != null) userBuckets.get(userId) { createBucket(rule) }
    //                        else ipBuckets.get(clientIp) { createBucket(rule) }

    // Endpoint bazlı rate limit kuralları
    // KRİTİK: Spesifik kurallar ÖNCE listelenmeli! firstOrNull() ilk eşleşeni döndürür.
    // Genel prefix kuralları sona konur, aksi halde /api/auth/login → /api/public/ kuralına düşer.
    private val rules = listOf(
        RateLimitRule("/api/auth/login",            5,  Duration.ofMinutes(1)),   // Brute force koruması
        RateLimitRule("/api/auth/register",          5,  Duration.ofMinutes(1)),   // DÜZELTME: Register rate limit
        RateLimitRule("/api/auth/refresh",           20, Duration.ofMinutes(1)),   // DÜZELTME: Refresh rate limit
        RateLimitRule("/api/auth/forgot-password",   3,  Duration.ofMinutes(1)),   // DÜZELTME: Password reset spam koruması
        RateLimitRule("/api/public/contact",         3,  Duration.ofMinutes(1)),   // Spam koruması
        RateLimitRule("/api/public/appointments",   10, Duration.ofMinutes(1)),
        RateLimitRule("/api/public/staff",           60, Duration.ofMinutes(1)),   // Personel listesi
        RateLimitRule("/api/public/availability",    60, Duration.ofMinutes(1)),   // Müsaitlik sorgusu
        RateLimitRule("/api/public/reviews",         60, Duration.ofMinutes(1)),   // Değerlendirmeler
        RateLimitRule("/api/admin/upload",           20, Duration.ofMinutes(1)),
        RateLimitRule("/api/admin/",               100, Duration.ofMinutes(1)),   // Genel admin
        RateLimitRule("/api/public/",              200, Duration.ofMinutes(1))    // Genel public
    )

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // DÜZELTME: Proxy arkasında gerçek IP'yi al (X-Forwarded-For header)
        val clientIp = request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.remoteAddr
        val uri = request.requestURI
        val rule = rules.firstOrNull { uri.startsWith(it.pathPrefix) } ?: run {
            filterChain.doFilter(request, response)
            return
        }

        val bucket = ipBuckets.get("$clientIp:${rule.pathPrefix}") {
            Bucket.builder()
                .addLimit(Bandwidth.simple(rule.limit.toLong(), rule.window))
                .build()
        }

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response)
        } else {
            response.status = 429
            response.contentType = "application/json"
            response.writer.write("""{"success":false,"error":"Çok fazla istek","code":"RATE_LIMIT_EXCEEDED"}""")
        }
    }

    data class RateLimitRule(val pathPrefix: String, val limit: Int, val window: Duration)
}

// build.gradle.kts'e ekle:
// implementation("com.bucket4j:bucket4j-core:8.10.1")
```

### 7.6 Dosya Yükleme Güvenliği

```kotlin
import org.apache.tika.Tika                              // DÜZELTME: Eksik import eklendi
import javax.imageio.ImageIO                              // DÜZELTME: Eksik import eklendi
import java.io.ByteArrayInputStream

@Service
class SecureFileUploadService(
    private val storageProvider: StorageProvider   // S3 veya MinIO
) {
    companion object {
        val ALLOWED_CONTENT_TYPES = setOf(
            "image/jpeg", "image/png", "image/webp", "image/avif"
        )
        val ALLOWED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "avif")
        const val MAX_FILE_SIZE = 10 * 1024 * 1024L  // 10MB
        const val MAX_IMAGE_DIMENSION = 4096           // Max 4096x4096 px
    }

    fun uploadImage(file: MultipartFile, directory: String): String {
        // 1. Boyut kontrolü
        if (file.size > MAX_FILE_SIZE) {
            throw IllegalArgumentException("Dosya boyutu 10MB'den büyük olamaz")
        }

        // 2. Uzantı kontrolü
        val extension = file.originalFilename?.substringAfterLast(".")?.lowercase()
        if (extension !in ALLOWED_EXTENSIONS) {
            throw IllegalArgumentException(
                "Desteklenmeyen dosya formatı: $extension. " +
                "İzin verilen: ${ALLOWED_EXTENSIONS.joinToString()}"
            )
        }

        // KRİTİK DÜZELTME: inputStream SADECE BİR KEZ okunabilir!
        // Birden fazla işlem için file.bytes kullanılmalı.
        val fileBytes = file.bytes

        // 3. MIME type kontrolü (content sniffing — uzantıya güvenme)
        // Apache Tika kütüphanesi ile gerçek dosya içeriğinden MIME type tespit et
        // build.gradle.kts: implementation("org.apache.tika:tika-core:2.9.1")
        val tika = Tika()
        val detectedType = tika.detect(fileBytes)
        if (detectedType !in ALLOWED_CONTENT_TYPES) {
            throw SecurityException("Dosya içeriği beyan edilen türle uyuşmuyor")
        }

        // 4. Görsel boyut kontrolü (decompression bomb koruması)
        val image = ImageIO.read(ByteArrayInputStream(fileBytes))
            ?: throw IllegalArgumentException("Geçersiz görsel dosyası")
        if (image.width > MAX_IMAGE_DIMENSION || image.height > MAX_IMAGE_DIMENSION) {
            throw IllegalArgumentException("Görsel boyutu ${MAX_IMAGE_DIMENSION}x${MAX_IMAGE_DIMENSION}'den büyük olamaz")
        }

        // 5. Güvenli dosya adı oluştur (path traversal koruması)
        val tenantId = TenantContext.getTenantId()
        val safeName = "${UUID.randomUUID()}.$extension"
        val path = "tenants/$tenantId/$directory/$safeName"

        // 6. Yükle (ayrı domain'den servis edilmeli — XSS koruması)
        return storageProvider.upload(ByteArrayInputStream(fileBytes), path, detectedType)
    }
}
```

> **GÜVENLİK NOTU:** Yüklenen dosyalar **ayrı bir domain**'den (örn: `cdn.app.com`) servis edilmelidir. Ana domain'den servis edilirse, SVG/HTML dosyaları XSS saldırısı vektörü olur.

### 7.6.1 StorageProvider Interface

```kotlin
import java.io.InputStream

/** Dosya depolama soyutlaması — Local, S3, MinIO implementasyonları değiştirilebilir */
interface StorageProvider {
    fun upload(inputStream: InputStream, path: String, contentType: String): String
    fun delete(path: String)
    fun getUrl(path: String): String
}

/**
 * Local dosya sistemi implementasyonu (development ortamı için).
 * Prodüksiyonda S3 veya MinIO tercih edilmeli.
 */
@Service
@Profile("dev")
class LocalStorageProvider(
    @Value("\${storage.local.base-path:/tmp/aesthetic-uploads}")
    private val basePath: String,
    @Value("\${storage.local.base-url:http://localhost:8080/uploads}")
    private val baseUrl: String
) : StorageProvider {

    override fun upload(inputStream: InputStream, path: String, contentType: String): String {
        val file = Path.of(basePath, path).toFile()
        file.parentFile.mkdirs()
        inputStream.use { it.copyTo(file.outputStream()) }
        return getUrl(path)
    }

    override fun delete(path: String) {
        Path.of(basePath, path).toFile().delete()
    }

    override fun getUrl(path: String): String = "$baseUrl/$path"
}

// Not: S3StorageProvider prodüksiyon implementasyonu ayrı bean olarak tanımlanır.
// @Service @Profile("prod") class S3StorageProvider(...) : StorageProvider { ... }
```

### 7.7 Rol Tabanlı Erişim Kontrolü

```kotlin
// Controller'da kullanım
@RestController
@RequestMapping("/api/admin/services")
class ServiceController(private val serviceService: ServiceManagementService) {

    @GetMapping
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'STAFF')")  // STAFF read-only erişim
    fun listServices(pageable: Pageable): PagedResponse<ServiceResponse> {
        // TENANT_ADMIN ve STAFF (read-only) görebilir
    }

    @PostMapping
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")  // DÜZELTME: hasRole → hasAuthority
    fun createService(@Valid @RequestBody request: CreateServiceRequest): ApiResponse<ServiceResponse> {
        // Sadece TENANT_ADMIN oluşturabilir
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")  // DÜZELTME: hasRole → hasAuthority
    fun deleteService(@PathVariable id: String): ApiResponse<Nothing> {
        // Sadece TENANT_ADMIN silebilir
    }
}
```

### 7.8 GlobalExceptionHandler

```kotlin
@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /** 400 — Validation hataları (Jakarta Bean Validation) */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val errors = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "Geçersiz değer") }
        return ResponseEntity.badRequest().body(
            ErrorResponse(error = "Doğrulama hatası", code = ErrorCode.VALIDATION_ERROR, details = errors)
        )
    }

    /** 400 — İş kuralı ihlali */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.badRequest().body(
            ErrorResponse(error = ex.message ?: "Geçersiz istek", code = ErrorCode.VALIDATION_ERROR)
        )
    }

    /** 401 — Yetkisiz erişim */
    @ExceptionHandler(AuthenticationException::class)
    fun handleAuth(ex: AuthenticationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ErrorResponse(error = "Kimlik doğrulama başarısız", code = ErrorCode.INVALID_CREDENTIALS)
        )
    }

    /** 403 — Yetersiz yetki (DÜZELTME AA-O9: errorCode → FORBIDDEN) */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleForbidden(ex: AccessDeniedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse(error = "Bu işlem için yetkiniz yok", code = ErrorCode.FORBIDDEN)
        )
    }

    /** 404 — Kaynak bulunamadı (custom exception) */
    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleResourceNotFound(ex: ResourceNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(error = ex.message ?: "Kayıt bulunamadı", code = ErrorCode.RESOURCE_NOT_FOUND)
        )
    }

    /** 404 — Tenant bulunamadı */
    @ExceptionHandler(TenantNotFoundException::class)
    fun handleTenantNotFound(ex: TenantNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(error = ex.message ?: "Tenant bulunamadı", code = ErrorCode.TENANT_NOT_FOUND)
        )
    }

    /** 409 — Randevu çakışması */
    @ExceptionHandler(AppointmentConflictException::class)
    fun handleAppointmentConflict(ex: AppointmentConflictException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(error = ex.message ?: "Seçilen zaman diliminde çakışma var", code = ErrorCode.APPOINTMENT_CONFLICT)
        )
    }

    /** 409 — Veri çakışması (DB unique constraint vb.) */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleConflict(ex: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(error = "Veri çakışması — bu kayıt zaten mevcut olabilir", code = ErrorCode.DUPLICATE_RESOURCE)
        )
    }

    /** 423 — Hesap kilitli */
    @ExceptionHandler(AccountLockedException::class)
    fun handleAccountLocked(ex: AccountLockedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.LOCKED).body(
            ErrorResponse(error = ex.message ?: "Hesap kilitli", code = ErrorCode.ACCOUNT_LOCKED)
        )
    }

    /** 403 — Plan limiti aşıldı (DÜZELTME T2: 429 → 403, RATE_LIMIT_EXCEEDED → PLAN_LIMIT_EXCEEDED) */
    @ExceptionHandler(PlanLimitExceededException::class)
    fun handlePlanLimit(ex: PlanLimitExceededException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse(error = ex.message ?: "Plan limiti aşıldı", code = ErrorCode.PLAN_LIMIT_EXCEEDED)
        )
    }

    /** 403 — Müşteri kara listede (no-show limit aşıldı) */
    @ExceptionHandler(ClientBlacklistedException::class)
    fun handleClientBlacklisted(ex: ClientBlacklistedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse(error = ex.message ?: "Müşteri kara listede", code = ErrorCode.CLIENT_BLACKLISTED)
        )
    }

    /** 500 — Beklenmeyen hatalar */
    @ExceptionHandler(Exception::class)
    fun handleGeneral(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Beklenmeyen hata", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(error = "Sunucu hatası — lütfen tekrar deneyin", code = ErrorCode.INTERNAL_ERROR)
        )
    }
}

// DÜZELTME: Hata handler'ları artık ErrorResponse (§6.5) dönüyor — ApiResponse sadece başarılı yanıtlar için.
// ErrorResponse alanları: error (mesaj), code (ErrorCode enum), details (Map<String,String>?), timestamp
```

---

## 8. Veritabanı Şeması (MySQL)

### 8.1 ER Diyagramı (İlişkiler)

```
                              ┌─────────────┐
                              │   tenants   │  (root — tenant_id FK olmaz)
                              └──────┬──────┘
                                     │ 1:N (tüm alt tablolar tenant_id ile bağlı)
          ┌──────────────────────────┼──────────────────────────┐
          ▼                          ▼                          ▼
   ┌──────────┐              ┌──────────────┐           ┌──────────────┐
   │  users   │              │   services   │           │   products   │
   └────┬─────┘              └──────┬───────┘           └──────────────┘
        │                           │
        │ 1:N                       │ N:1
        ▼                           ▼
 ┌─────────────┐         ┌───────────────────┐
 │ client_notes│         │service_categories │
 └─────────────┘         └───────────────────┘

   users ───1:N(staff)──→ appointments ←──N:M──→ services
   users ───1:N(client)─→ appointments          (pivot: appointment_services)
                               │
                          ┌────┴────┐
                          ▼         ▼
                    ┌─────────┐ ┌──────────────────┐
                    │ reviews │ │appointment_services│
                    └─────────┘ └──────────────────┘

   ┌────────────┐  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐
   │ blog_posts │  │ gallery_items │  │contact_messages│  │ site_settings │
   └────────────┘  └───────────────┘  └───────────────┘  └───────────────┘

   ┌───────────────┐  ┌──────────────┐  ┌──────────────┐
   │working_hours  │  │blocked_time_ │  │refresh_tokens│
   │(staff + genel)│  │   slots      │  │(per user)    │
   └───────────────┘  └──────────────┘  └──────────────┘

   ┌──────────┐  ┌──────────────┐  ┌───────────────┐  ┌──────────────────┐
   │ payments │  │subscriptions │  │   invoices    │  │  notifications   │
   │ (iyzico) │  │(per tenant)  │  │(per payment)  │  │notification_tmpls│
   └──────────┘  └──────────────┘  └───────────────┘  └──────────────────┘

   İlişki özeti:
   • tenants 1:N → users, services, products, blog_posts, gallery_items, ...
   • users   1:N → appointments (staff), appointments (client), reviews, client_notes
   • services N:1 → service_categories
   • appointments N:M → services (pivot: appointment_services)
   • appointments 1:1 → reviews
   • tenants 1:1 → subscriptions, site_settings
   • Tüm tenant-scoped tablolar: tenant_id sütunu + Hibernate @Filter
```

### 8.2 Kritik Indexler

```sql
-- ═══════════════════════════════════════════════════
-- RANDEVU SİSTEMİ
-- ═══════════════════════════════════════════════════

-- Çakışma sorgularını hızlandırmak için (PESSIMISTIC_WRITE ile kullanılır)
CREATE INDEX idx_appt_conflict
    ON appointments(tenant_id, staff_id, date, start_time, end_time, status);

-- Müşteri randevu geçmişi
CREATE INDEX idx_appt_client ON appointments(tenant_id, client_id);

-- ═══════════════════════════════════════════════════
-- TENANT ÇÖZÜMLEME
-- ═══════════════════════════════════════════════════

CREATE UNIQUE INDEX idx_tenant_slug ON tenants(slug);
-- NOT: MySQL partial index desteklemez! NULL custom_domain'ler unique constraint'ten
-- otomatik hariç tutulur (MySQL'de NULL != NULL). Bu yüzden basit UNIQUE yeterlidir.
CREATE UNIQUE INDEX idx_tenant_custom_domain ON tenants(custom_domain);

-- ═══════════════════════════════════════════════════
-- KULLANICI + AUTH
-- ═══════════════════════════════════════════════════

CREATE UNIQUE INDEX idx_user_email_tenant ON users(email, tenant_id);

-- Refresh token sorguları
CREATE INDEX idx_rt_user ON refresh_tokens(user_id);
CREATE INDEX idx_rt_family ON refresh_tokens(family);
CREATE INDEX idx_rt_expires ON refresh_tokens(expires_at);

-- ═══════════════════════════════════════════════════
-- SEO SLUG'LAR (tenant bazlı unique)
-- ═══════════════════════════════════════════════════

CREATE UNIQUE INDEX idx_service_slug_tenant ON services(slug, tenant_id);
CREATE UNIQUE INDEX idx_category_slug_tenant ON service_categories(slug, tenant_id);
CREATE UNIQUE INDEX idx_product_slug_tenant ON products(slug, tenant_id);
CREATE UNIQUE INDEX idx_blog_slug_tenant ON blog_posts(slug, tenant_id);

-- ═══════════════════════════════════════════════════
-- YENİ ENTITY'LER
-- ═══════════════════════════════════════════════════

-- Review sorguları
CREATE INDEX idx_review_service ON reviews(tenant_id, service_id);
CREATE INDEX idx_review_staff ON reviews(tenant_id, staff_id);

-- Payment sorguları
CREATE INDEX idx_payment_tenant ON payments(tenant_id, status);
CREATE INDEX idx_sub_tenant_status ON subscriptions(tenant_id, status);  -- DÜZELTME: V12 migration ile tutarlı (composite index)

-- Çalışma saatleri + bloklanmış slotlar
CREATE UNIQUE INDEX uk_working_hours ON working_hours(tenant_id, staff_id, day_of_week);
CREATE INDEX idx_blocked_slot ON blocked_time_slots(tenant_id, staff_id, date);
```

### 8.3 Flyway Migration Örnekleri

> **Collation NOTU:** Tüm tablolarda `utf8mb4_turkish_ci` kullanılır. Bu sayede Türkçe
> karakterler (İ, ı, Ş, ş, Ö, ö, Ü, ü, Ç, ç, Ğ, ğ) doğru sıralanır ve aranır.

> **FK ON DELETE stratejisi:**
> - `ON DELETE CASCADE` → Alt kayıt otomatik silinir (tenant silinince tüm verileri silinir)
> - `ON DELETE SET NULL` → FK null yapılır (staff silinince randevudaki staff_id null olur)
> - `ON DELETE RESTRICT` → Silmeyi engeller (referans varsa silme yapılamaz)

```sql
-- V1__create_tenant_table.sql
CREATE TABLE tenants (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    slug VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    business_type ENUM('BEAUTY_CLINIC','DENTAL_CLINIC','BARBER_SHOP','HAIR_SALON','DIETITIAN','PHYSIOTHERAPIST','MASSAGE_SALON','VETERINARY','GENERAL') NOT NULL,
    phone VARCHAR(20) DEFAULT '',
    email VARCHAR(255) DEFAULT '',
    address TEXT DEFAULT '',
    logo_url VARCHAR(500),
    custom_domain VARCHAR(255) UNIQUE,           -- DÜZELTME: domain → custom_domain
    plan ENUM('TRIAL','STARTER','PROFESSIONAL','BUSINESS','ENTERPRISE') DEFAULT 'TRIAL',
    trial_end_date DATE,                         -- DÜZELTME: Eksik alan eklendi
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V2__create_user_table.sql
CREATE TABLE users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NULL,              -- STAFF dahil tüm roller, TENANT_ADMIN şifre belirler
    phone VARCHAR(20),
    image VARCHAR(500),
    role ENUM('PLATFORM_ADMIN','TENANT_ADMIN','STAFF','CLIENT') NOT NULL DEFAULT 'CLIENT',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    failed_login_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP(6) NULL,
    no_show_count INT NOT NULL DEFAULT 0,          -- No-show takibi (3 kez → kara liste)
    is_blacklisted BOOLEAN NOT NULL DEFAULT FALSE,
    blacklisted_at TIMESTAMP(6) NULL,
    blacklist_reason VARCHAR(255) NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    UNIQUE INDEX uk_user_email_tenant (email, tenant_id),
    INDEX idx_user_tenant_role (tenant_id, role),
    INDEX idx_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V3__create_service_tables.sql
CREATE TABLE service_categories (                -- DÜZELTME: Eksik tablo eklendi
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    image VARCHAR(500),
    sort_order INT DEFAULT 0,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    UNIQUE KEY uk_category_slug_tenant (slug, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V4__create_service_table.sql
CREATE TABLE services (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    slug VARCHAR(200) NOT NULL,
    title VARCHAR(255) NOT NULL,
    category_id VARCHAR(36),
    short_description VARCHAR(500) DEFAULT '',
    description TEXT DEFAULT '',
    price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(3) NOT NULL DEFAULT 'TRY',
    duration_minutes INT NOT NULL DEFAULT 30,
    buffer_minutes INT NOT NULL DEFAULT 0,
    image VARCHAR(500),
    recovery TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT DEFAULT 0,
    meta_title VARCHAR(255),
    meta_description VARCHAR(500),
    og_image VARCHAR(500),
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (category_id) REFERENCES service_categories(id) ON DELETE SET NULL,
    UNIQUE KEY uk_service_slug_tenant (slug, tenant_id),
    INDEX idx_service_tenant (tenant_id),
    INDEX idx_service_category (tenant_id, category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ElementCollection tabloları (service_benefits + service_process_steps)
CREATE TABLE service_benefits (
    service_id VARCHAR(36) NOT NULL,
    benefit VARCHAR(500) NOT NULL,
    FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE service_process_steps (
    service_id VARCHAR(36) NOT NULL,
    step VARCHAR(500) NOT NULL,
    FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V5__create_product_table.sql
CREATE TABLE products (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    slug VARCHAR(200) NOT NULL,
    name VARCHAR(255) NOT NULL,
    brand VARCHAR(255) DEFAULT '',
    category VARCHAR(100) DEFAULT '',
    description TEXT DEFAULT '',
    price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(3) NOT NULL DEFAULT 'TRY',
    image VARCHAR(500),
    stock_quantity INT,                                    -- NULL = stok takibi yok
    low_stock_threshold INT NOT NULL DEFAULT 5,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT DEFAULT 0,
    meta_title VARCHAR(255),
    meta_description VARCHAR(500),
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    UNIQUE KEY uk_product_slug_tenant (slug, tenant_id),
    INDEX idx_product_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE product_features (
    product_id VARCHAR(36) NOT NULL,
    feature VARCHAR(500) NOT NULL,
    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V6__create_blog_and_gallery_tables.sql
CREATE TABLE blog_posts (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    slug VARCHAR(200) NOT NULL,
    title VARCHAR(255) NOT NULL,
    excerpt TEXT DEFAULT '',
    content LONGTEXT DEFAULT '',
    author_id VARCHAR(36),
    category VARCHAR(100) DEFAULT '',
    image VARCHAR(500),
    read_time VARCHAR(50) DEFAULT '',
    is_published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMP(6) NULL,
    meta_title VARCHAR(255),
    meta_description VARCHAR(500),
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE KEY uk_blog_slug_tenant (slug, tenant_id),
    INDEX idx_blog_tenant (tenant_id),
    INDEX idx_blog_published (tenant_id, is_published)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE blog_post_tags (
    blog_post_id VARCHAR(36) NOT NULL,
    tag VARCHAR(100) NOT NULL,
    FOREIGN KEY (blog_post_id) REFERENCES blog_posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE gallery_items (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    category VARCHAR(100) DEFAULT '',
    service_id VARCHAR(36),
    before_image VARCHAR(500) NOT NULL,
    after_image VARCHAR(500) NOT NULL,
    description VARCHAR(500) DEFAULT '',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE SET NULL,
    INDEX idx_gallery_tenant (tenant_id),
    INDEX idx_gallery_category (tenant_id, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V7__create_appointment_tables.sql
CREATE TABLE appointments (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    client_id VARCHAR(36),
    client_name VARCHAR(255) NOT NULL,
    client_email VARCHAR(255) NOT NULL,
    client_phone VARCHAR(20) NOT NULL,
    primary_service_id VARCHAR(36),              -- DÜZELTME: service_id → primary_service_id
    staff_id VARCHAR(36),
    date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    total_duration_minutes INT DEFAULT 0,        -- DÜZELTME: Eksik alanlar eklendi
    total_price DECIMAL(10,2) DEFAULT 0.00,
    notes TEXT,
    status ENUM('PENDING','CONFIRMED','IN_PROGRESS','COMPLETED','CANCELLED','NO_SHOW')
        DEFAULT 'PENDING',
    cancelled_at TIMESTAMP(6) NULL,
    cancellation_reason VARCHAR(500),
    recurring_group_id VARCHAR(36),              -- Tekrarlayan randevu grubu
    recurrence_rule VARCHAR(20),                 -- WEEKLY, BIWEEKLY, MONTHLY
    reminder_24h_sent BOOLEAN DEFAULT FALSE,     -- Hatırlatıcı durumları
    reminder_1h_sent BOOLEAN DEFAULT FALSE,
    version BIGINT DEFAULT 0,                    -- Optimistic locking
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (client_id) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (primary_service_id) REFERENCES services(id) ON DELETE SET NULL,
    FOREIGN KEY (staff_id) REFERENCES users(id) ON DELETE SET NULL,

    INDEX idx_appt_conflict (tenant_id, staff_id, date, start_time, end_time, status),
    INDEX idx_appt_tenant_date (tenant_id, date),      -- DÜZELTME: Entity'deki eksik index eklendi
    INDEX idx_appt_status (tenant_id, status),          -- DÜZELTME: Entity'deki eksik index eklendi
    INDEX idx_appt_client (tenant_id, client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- Pivot tablo: Randevu ↔ Hizmet (çoklu hizmet desteği)
CREATE TABLE appointment_services (              -- DÜZELTME: Eksik pivot tablo eklendi
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    appointment_id VARCHAR(36) NOT NULL,
    service_id VARCHAR(36) NOT NULL,
    price DECIMAL(10,2) DEFAULT 0.00,            -- Randevu anındaki fiyat snapshot
    duration_minutes INT DEFAULT 0,
    sort_order INT DEFAULT 0,

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (appointment_id) REFERENCES appointments(id) ON DELETE CASCADE,
    FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE RESTRICT,

    INDEX idx_appt_svc (appointment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE working_hours (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    staff_id VARCHAR(36),
    day_of_week ENUM('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY') NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    break_start_time TIME,
    break_end_time TIME,
    is_working_day BOOLEAN DEFAULT TRUE,

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (staff_id) REFERENCES users(id) ON DELETE CASCADE,

    UNIQUE KEY uk_working_hours (tenant_id, staff_id, day_of_week)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE blocked_time_slots (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    staff_id VARCHAR(36),
    date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    reason VARCHAR(500),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (staff_id) REFERENCES users(id) ON DELETE CASCADE,

    INDEX idx_blocked_slot (tenant_id, staff_id, date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V8__create_contact_table.sql
CREATE TABLE contact_messages (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(20),
    subject VARCHAR(255) DEFAULT '',
    message TEXT NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    replied_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    INDEX idx_contact_tenant (tenant_id),
    INDEX idx_contact_read (tenant_id, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V9__create_settings_table.sql
CREATE TABLE site_settings (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL UNIQUE,         -- Her tenant'ın tek bir settings kaydı
    site_name VARCHAR(255) DEFAULT '',
    phone VARCHAR(20) DEFAULT '',
    email VARCHAR(255) DEFAULT '',
    address TEXT DEFAULT '',
    whatsapp VARCHAR(20) DEFAULT '',
    instagram VARCHAR(255) DEFAULT '',
    facebook VARCHAR(255) DEFAULT '',
    twitter VARCHAR(255) DEFAULT '',
    youtube VARCHAR(255) DEFAULT '',
    map_embed_url VARCHAR(1000) DEFAULT '',
    timezone VARCHAR(50) DEFAULT 'Europe/Istanbul',
    locale VARCHAR(10) DEFAULT 'tr',
    cancellation_policy_hours INT NOT NULL DEFAULT 24,
    default_slot_duration_minutes INT NOT NULL DEFAULT 30,   -- DÜZELTME: Eksik DDL eklendi (AvailabilityService'te kullanılır)
    theme_settings JSON DEFAULT ('{}'),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V10__create_review_table.sql
CREATE TABLE reviews (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    appointment_id VARCHAR(36),
    client_id VARCHAR(36) NOT NULL,
    service_id VARCHAR(36),
    staff_id VARCHAR(36),
    rating INT NOT NULL DEFAULT 0,
    comment TEXT,
    is_approved BOOLEAN DEFAULT FALSE,
    is_public BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (appointment_id) REFERENCES appointments(id) ON DELETE SET NULL,
    FOREIGN KEY (client_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE SET NULL,
    FOREIGN KEY (staff_id) REFERENCES users(id) ON DELETE SET NULL,

    INDEX idx_review_service (tenant_id, service_id),
    INDEX idx_review_staff (tenant_id, staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V11__create_refresh_token_table.sql
CREATE TABLE refresh_tokens (
    id VARCHAR(36) NOT NULL PRIMARY KEY,         -- JWT jti claim
    user_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    family VARCHAR(36) NOT NULL,                 -- Token ailesi (theft detection)
    expires_at TIMESTAMP(6) NOT NULL,
    is_revoked BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,   -- DÜZELTME: Eksik tenant FK

    INDEX idx_rt_user (user_id),
    INDEX idx_rt_family (family),
    INDEX idx_rt_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V12__create_payment_tables.sql
CREATE TABLE subscriptions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    plan VARCHAR(20) NOT NULL DEFAULT 'TRIAL',            -- TRIAL, STARTER, PROFESSIONAL, BUSINESS, ENTERPRISE
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    auto_renew BOOLEAN NOT NULL DEFAULT TRUE,
    max_staff INT NOT NULL DEFAULT 1,
    max_appointments_per_month INT NOT NULL DEFAULT 100,  -- TRIAL limiti: 100
    max_storage_mb INT NOT NULL DEFAULT 500,              -- TRIAL limiti: 500MB
    monthly_price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    billing_period VARCHAR(20) NOT NULL DEFAULT 'MONTHLY',  -- MONTHLY, YEARLY
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',         -- ACTIVE, PAST_DUE, CANCELLED, EXPIRED  -- DÜZELTME: SUSPENDED → PAST_DUE (entity enum ile tutarlı)
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    INDEX idx_sub_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE payments (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    subscription_id VARCHAR(36),
    amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(3) NOT NULL DEFAULT 'TRY',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',        -- PENDING, COMPLETED, FAILED, REFUNDED
    provider VARCHAR(20) NOT NULL DEFAULT 'IYZICO',       -- IYZICO, STRIPE
    provider_payment_id VARCHAR(255),
    provider_subscription_id VARCHAR(255),
    failure_reason TEXT,
    paid_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE SET NULL,
    -- DÜZELTME: idx_pay_tenant kaldırıldı — idx_pay_status leftmost prefix ile aynı görevi görür
    INDEX idx_pay_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE invoices (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36),
    invoice_number VARCHAR(50) NOT NULL,
    amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(3) NOT NULL DEFAULT 'TRY',      -- DÜZELTME: Entity field eklendi
    tax_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    tax_rate INT NOT NULL DEFAULT 20,                -- DÜZELTME: Entity field eklendi (%KDV)
    total_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    billing_name VARCHAR(255) NOT NULL DEFAULT '',    -- DÜZELTME: Entity field eklendi
    billing_address TEXT,                             -- DÜZELTME: Entity field eklendi
    tax_id VARCHAR(20),                               -- DÜZELTME: Entity field eklendi (Vergi No)
    pdf_url VARCHAR(500),
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE SET NULL,
    UNIQUE INDEX idx_inv_number (invoice_number),
    INDEX idx_inv_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V13__create_notification_tables.sql
CREATE TABLE notification_templates (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    type VARCHAR(50) NOT NULL,                            -- APPOINTMENT_CONFIRMATION, REMINDER_24H vb.
    email_subject VARCHAR(255),
    email_body TEXT,                                       -- HTML template (Mustache syntax)
    sms_body VARCHAR(320),                                -- Multi-part SMS (2×160)
    is_email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_sms_enabled BOOLEAN NOT NULL DEFAULT FALSE,

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    UNIQUE INDEX idx_nt_tenant_type (tenant_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE notification_logs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    appointment_id VARCHAR(36),
    recipient_email VARCHAR(255),
    recipient_phone VARCHAR(20),
    channel VARCHAR(10) NOT NULL DEFAULT 'EMAIL',         -- EMAIL, SMS
    type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',        -- PENDING, SENT, FAILED
    error_message TEXT,
    retry_count INT NOT NULL DEFAULT 0,               -- DÜZELTME: Entity field eklendi (NotificationLog.retryCount)
    sent_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    INDEX idx_nl_tenant (tenant_id),
    INDEX idx_nl_appointment (appointment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V14__create_client_notes_table.sql
CREATE TABLE client_notes (                      -- DÜZELTME: Eksik tablo eklendi
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    client_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (client_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE,

    INDEX idx_client_notes (tenant_id, client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V15__create_audit_log_table.sql
-- Not: AuditLog TenantAwareEntity'den extend ETMEZ (platform admin tüm tenant loglarını görebilir)
CREATE TABLE audit_logs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    action VARCHAR(100) NOT NULL,                         -- CREATE_APPOINTMENT, UPDATE_SERVICE vb.
    entity_type VARCHAR(50) NOT NULL,                     -- Appointment, Service vb.
    entity_id VARCHAR(36) NOT NULL,
    details JSON,                                         -- Değişen alanlar (JSON)
    ip_address VARCHAR(45),                               -- IPv4 + IPv6
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    INDEX idx_audit_tenant (tenant_id, created_at),
    INDEX idx_audit_entity (entity_type, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V16__create_consent_records_table.sql
CREATE TABLE consent_records (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    consent_type VARCHAR(30) NOT NULL,                    -- TERMS_OF_SERVICE, PRIVACY_POLICY, MARKETING_EMAIL vb.
    is_granted BOOLEAN NOT NULL DEFAULT FALSE,
    granted_at TIMESTAMP(6) NULL,
    revoked_at TIMESTAMP(6) NULL,
    ip_address VARCHAR(45),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_consent_tenant_user (tenant_id, user_id),
    INDEX idx_consent_type (tenant_id, consent_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V17__create_subscription_modules_table.sql
CREATE TABLE subscription_modules (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    subscription_id VARCHAR(36) NOT NULL,
    module_key VARCHAR(30) NOT NULL,              -- BLOG, PRODUCTS, GALLERY, PATIENT_RECORDS, ...
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    monthly_price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    activated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deactivated_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE,

    UNIQUE KEY uk_sub_module (subscription_id, module_key),
    INDEX idx_sub_modules_sub (subscription_id),
    INDEX idx_sub_modules_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- V18__create_patient_records_table.sql
CREATE TABLE patient_records (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    client_id VARCHAR(36) NOT NULL,
    structured_data JSON DEFAULT '{}',            -- Sektöre göre değişen yapılandırılmış veri
    general_notes TEXT NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (client_id) REFERENCES users(id) ON DELETE CASCADE,

    UNIQUE KEY uk_patient_client_tenant (client_id, tenant_id),
    INDEX idx_patient_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE treatment_history (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    patient_record_id VARCHAR(36) NOT NULL,
    appointment_id VARCHAR(36) NULL,
    performed_by VARCHAR(36) NULL,
    treatment_date DATE NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    treatment_data JSON DEFAULT '{}',             -- Tedaviye özgü yapılandırılmış veri
    attachments JSON DEFAULT '[]',                -- Dosya ekleri listesi
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),

    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (patient_record_id) REFERENCES patient_records(id) ON DELETE CASCADE,
    FOREIGN KEY (appointment_id) REFERENCES appointments(id) ON DELETE SET NULL,
    FOREIGN KEY (performed_by) REFERENCES users(id) ON DELETE SET NULL,

    INDEX idx_treatment_patient (patient_record_id),
    INDEX idx_treatment_date (tenant_id, treatment_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- DÜZELTME: V19 kaldırıldı — monthly_price ve billing_period zaten V12'de (CREATE TABLE subscriptions) tanımlı.
-- NOT: enabled_modules JSON alanı kullanılmaz — SubscriptionModule entity (relational) tercih edilir.
-- NOT: V20 kaldırıldı — password_hash zaten CREATE TABLE'da NULL olarak tanımlandı.
-- NOT: no_show_count, is_blacklisted vb. zaten CREATE TABLE'da tanımlandı.

-- V20__alter_tenants_expand_business_type.sql
-- Yeni sektörler: DIETITIAN, PHYSIOTHERAPIST, MASSAGE_SALON, VETERINARY, GENERAL
-- Plan değişiklikleri: BASIC → STARTER, yeni BUSINESS
ALTER TABLE tenants
    MODIFY COLUMN business_type ENUM(
        'BEAUTY_CLINIC','DENTAL_CLINIC','BARBER_SHOP','HAIR_SALON',
        'DIETITIAN','PHYSIOTHERAPIST','MASSAGE_SALON','VETERINARY','GENERAL'
    ) NOT NULL,
    MODIFY COLUMN plan ENUM('TRIAL','STARTER','PROFESSIONAL','BUSINESS','ENTERPRISE') DEFAULT 'TRIAL';

-- Mevcut BASIC planları STARTER'a güncelle
UPDATE tenants SET plan = 'STARTER' WHERE plan = 'BASIC';

-- DÜZELTME F1: V21__create_staff_services_table.sql
-- Personel-hizmet many-to-many ilişkisi (hangi personel hangi hizmetleri sunuyor)
CREATE TABLE staff_services (
    staff_id VARCHAR(36) NOT NULL,
    service_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (staff_id, service_id),
    FOREIGN KEY (staff_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE,
    INDEX idx_staff_services_service (service_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- DÜZELTME F8: V22__alter_site_settings_add_auto_confirm.sql
ALTER TABLE site_settings
    ADD COLUMN auto_confirm_appointments BOOLEAN NOT NULL DEFAULT FALSE;

-- DÜZELTME: V23__create_processed_webhook_events_table.sql (Webhook idempotency)
CREATE TABLE processed_webhook_events (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL UNIQUE,      -- Provider'dan gelen event ID
    provider VARCHAR(50) NOT NULL,               -- IYZICO, STRIPE
    event_type VARCHAR(100) NOT NULL,            -- payment.success, subscription.cancelled
    processed_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_webhook_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;
```

---

## 9. Konfigürasyon

### 9.1 application.yml

```yaml
spring:
  application:
    name: aesthetic-saas-backend

  datasource:
    # DB sütunları UTC saklar — tenant timezone dönüşümü uygulama katmanında yapılır
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:aesthetic_saas}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 30               # Geliştirme. Prod: application-prod.yml'de 50
      minimum-idle: 5
      idle-timeout: 300000
      connection-timeout: 20000
      leak-detection-threshold: 60000     # 60s — connection leak tespiti

  jpa:
    hibernate:
      ddl-auto: validate     # Flyway yönetir, Hibernate sadece doğrular
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
        format_sql: true
        default_batch_fetch_size: 20
    open-in-view: false       # Performans için kapalı

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0             # DÜZELTME: FlywayConfig.kt custom bean ile tutarlı

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 2000               # Connection timeout (ms)

  cache:
    type: caffeine                # Local cache (tenant resolution, entity cache)
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=300s    # 5 dakika (tüm cache'ler için ortak)
    cache-names: services,products,blog,gallery,tenants
    # NOT: Per-cache TTL gerekirse custom CaffeineCacheManager @Bean tanımlanmalı.
    # TenantCacheManager eviction'ı CacheManager üzerinden yapılmalı (Redis değil — Bölüm 13.3).

  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB

  jackson:
    serialization:
      write-dates-as-timestamps: false
    time-zone: UTC              # API yanıtları UTC — tenant timezone uygulama katmanında yönetilir

# JWT
jwt:
  secret: ${JWT_SECRET}                   # Zorunlu! Min 256-bit key. Güvenlik için default yok.
  access-token-expiration: 3600000        # 1 saat (ms) — tüm login yapabilen roller aynı
  # Refresh token süreleri rol bazlı:
  refresh-token-expiration:
    platform-admin: 86400000              # 1 gün (ms) — en hassas hesap
    tenant-admin: 2592000000              # 30 gün (ms) — günlük işletme yönetimi
    client: 5184000000                    # 60 gün (ms) — kullanıcı deneyimi
    staff: 604800000                      # 7 gün (ms) — DÜZELTME F9: STAFF kısıtlı login

# DÜZELTME: file: bloğu kaldırıldı — storage: bloğu (aşağıda) StorageProvider tarafından kullanılır.
# Eski file.upload.* property'leri artık storage.* altındadır.

# Logging — DÜZELTME: İki logging bloğu tek blokta birleştirildi (level + pattern)
logging:
  level:
    root: INFO
    com.aesthetic.backend: DEBUG
    # Hibernate SQL logları — sadece application-dev.yml'de aktif edin:
    # org.hibernate.SQL: DEBUG
    # org.hibernate.orm.jdbc.bind: TRACE     # Hibernate 6'da BasicBinder yerine orm.jdbc.bind
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] [%X{correlationId}] [tenant=%X{tenantId}] %-5level %logger{36} - %msg%n"

# Notification (Bölüm 18)
notification:
  provider: sendgrid
  api-key: ${SENDGRID_API_KEY:}
  from-email: ${NOTIFICATION_FROM_EMAIL:noreply@aestheticclinic.com}
  from-name: ${NOTIFICATION_FROM_NAME:Aesthetic Clinic}
  sms:
    provider: netgsm
    username: ${NETGSM_USERNAME:}
    password: ${NETGSM_PASSWORD:}
    sender-id: ${NETGSM_SENDER_ID:}

# Sentry (error tracking — Bölüm 25)
sentry:
  dsn: ${SENTRY_DSN:}
  environment: ${SPRING_PROFILES_ACTIVE:dev}
  traces-sample-rate: 1.0

# Actuator & Prometheus (Bölüm 14)
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
  endpoint:
    health:
      show-details: when_authorized
  metrics:
    tags:
      application: aesthetic-backend

# CORS
app:
  frontend-url: ${FRONTEND_URL:http://localhost:3000}

# DÜZELTME: iyzico konfigürasyonu eklendi (WebhookController @Value ile kullanıyor)
iyzico:
  api-key: ${IYZICO_API_KEY:}
  secret-key: ${IYZICO_SECRET_KEY:}
  base-url: ${IYZICO_BASE_URL:https://sandbox-api.iyzipay.com}

# DÜZELTME: Dosya yükleme — StorageProvider @Value ile kullanıyor
storage:
  provider: ${FILE_PROVIDER:local}     # local | s3
  local:
    base-path: ${FILE_UPLOAD_PATH:./uploads}
    base-url: ${FILE_UPLOAD_URL:http://localhost:8080/uploads}

server:
  port: ${SERVER_PORT:8080}
```

### 9.1.1 application-dev.yml

Geliştirme ortamında SQL logları ve detaylı hata ayıklama aktif edilir. `application.yml`'deki default değerleri override eder:

```yaml
# application-dev.yml — Geliştirme ortamı overrides
spring:
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true

logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE       # Hibernate 6 — bind parameter logları
    com.aesthetic.backend: TRACE
```

### 9.2 build.gradle.kts

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    kotlin("plugin.jpa") version "2.0.21"
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.aesthetic"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Database
    runtimeOnly("com.mysql:mysql-connector-j")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Password Hashing — spring-boot-starter-security içinde BCryptPasswordEncoder zaten mevcut

    // OpenAPI / Swagger
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // File Upload (MIME type detection — Bölüm 7.6'da Tika().detect() kullanılıyor)
    implementation("org.apache.tika:tika-core:3.0.0")

    // Redis (rate limiting + distributed cache)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Cache (Caffeine local cache — tenant resolution, entity cache)
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // Rate Limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // Retry (@EnableRetry — AOP starter gerekli)
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // iyzico (Türkiye ödeme)
    implementation("com.iyzipay:iyzipay-java:2.0.131")

    // SendGrid (e-posta bildirimleri — Bölüm 18)
    implementation("com.sendgrid:sendgrid-java:4.10.2")    // DÜZELTME AA-T4: 4.10.1 → 4.10.2 tutarlılaştırıldı

    // Micrometer Prometheus (Actuator metrics export — Bölüm 14)
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Sentry (error tracking)
    implementation("io.sentry:sentry-spring-boot-starter-jakarta:7.14.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.testcontainers:testcontainers:1.20.3")
    testImplementation("org.testcontainers:mysql:1.20.3")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")    // Spring null-safety desteği
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

### 9.3 FlywayConfig.kt

```kotlin
// FlywayConfig.kt — Flyway migration konfigürasyonu
// NOT AA-O7: spring.flyway.* YAML ayarları da mevcuttur (Bölüm 9.1). Custom bean bunları override eder.
// ÖNERİ: Bu sınıf kaldırılarak sadece YAML konfigürasyonu kullanılması önerilir.
// spring.flyway.baseline-version: 0 ve diğer ayarlar zaten YAML'de mevcuttur.
// Custom bean, YAML ayarlarını gizler ve iki kaynak arasında tutarsızlık riski oluşturur.
@Configuration
class FlywayConfig {

    @Bean
    fun flyway(dataSource: DataSource): Flyway {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)           // Mevcut DB varsa baseline al
            .baselineVersion("0")              // Başlangıç versiyonu
            .validateOnMigrate(true)           // Migration checksum doğrulama
            .encoding(Charsets.UTF_8)
            .table("flyway_schema_history")    // Flyway tracking tablosu
            .load()
            .also { it.migrate() }
    }
}
```

---

## 10. Docker & Deployment

### 10.1 docker-compose.yml

```yaml
# Docker Compose V2 — 'version' alanı artık gerekli değil (deprecated)
services:
  app:
    build: ../../Desktop/yakup/aesthetic-clinic
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=dev               # DÜZELTME: Dev profili aktif (application-dev.yml yüklenir)
      - DB_HOST=mysql
      - DB_PORT=3306
      - DB_NAME=aesthetic_saas
      - DB_USERNAME=root
      - DB_PASSWORD=${DB_PASSWORD:-secret}       # .env dosyasından al
      - JWT_SECRET=${JWT_SECRET}                 # Zorunlu! .env dosyasında tanımla
      - FILE_PROVIDER=local
      - REDIS_HOST=redis
      - REDIS_PORT=6379
      - REDIS_PASSWORD=${REDIS_PASSWORD:-}
      - JAVA_OPTS=-Xmx512m -Xms256m             # JVM memory ayarları
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy               # DÜZELTME: service_started → service_healthy (Redis hazır olana kadar bekle)
    volumes:
      - uploads:/app/uploads
    restart: unless-stopped
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:8080/actuator/health" ]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s                          # Spring Boot başlayana kadar bekle

  mysql:
    image: mysql:8.0
    ports:
      - "3306:3306"
    environment:
      - MYSQL_ROOT_PASSWORD=${DB_PASSWORD:-secret}
      - MYSQL_DATABASE=aesthetic_saas
    command: # utf8mb4_turkish_ci (Bölüm 8 ile tutarlı)
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_turkish_ci
    volumes:
      - mysql-data:/var/lib/mysql
    restart: unless-stopped
    healthcheck:
      test: [ "CMD", "mysqladmin", "ping", "-h", "localhost" ]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    restart: unless-stopped
    healthcheck: # DÜZELTME: Redis healthcheck eklendi
      test: [ "CMD", "redis-cli", "ping" ]
      interval: 10s
      timeout: 5s
      retries: 3
    volumes:
      - redis-data:/data

volumes:
  mysql-data:
  uploads:
  redis-data:
```

### 10.2 Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app
COPY build/libs/*.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser
EXPOSE 8080

# JAVA_OPTS env ile JVM parametreleri dışarıdan ayarlanabilir
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### 10.3 Multi-Stage Build (Prodüksiyon)

> **Not:** Aşağıdaki `.dockerignore` dosyasını proje kök dizinine ekleyin:

```text
# .dockerignore — Multi-stage build için (multi-stage container içinde build yapar)
# NOT: Simple Dockerfile kullanıyorsanız build/ satırını kaldırın (jar kopyalamak için gerekli)
.git/
.gitignore
.idea/
.vscode/
*.md
build/                  # Multi-stage'de gerekli değil; simple Dockerfile'da KALDIRILMALI
out/
node_modules/
docker-compose*.yml
.env*
src/test/
```

```dockerfile
# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
# Önce dependency'leri cache'le (layer optimization)
COPY build.gradle.kts settings.gradle.kts gradlew ./    # DÜZELTME: gradlew eksikti → build hata verirdi
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
# Sonra kodu kopyala ve build et
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app
COPY --from=builder /build/build/libs/*.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser
EXPOSE 8080

# JAVA_OPTS env ile JVM parametreleri dışarıdan ayarlanabilir (docker-compose'da tanımlı)
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

---

## 11. Frontend Entegrasyonu

### 11.1 Next.js Frontend'den API Çağrıları

Mevcut Next.js frontend, şu anda kendi API route'larını (`app/api/*`) kullanıyor. Spring Boot backend'e geçişte:

```
Mevcut:   Next.js → app/api/services/route.ts → Prisma → PostgreSQL
Yeni:     Next.js → Spring Boot API → JPA → MySQL
```

**Geçiş stratejisi:**

1. Next.js `app/api/` route'larını Spring Boot URL'lerine proxy olarak yönlendir
2. Veya doğrudan frontend'den Spring Boot API'ye istek at (CORS ayarlı)

```typescript
// lib/api-client.ts (Next.js tarafı)
// HTTPS zorunlu — prod'da http:// kullanmayın
const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';

// Tenant çözünürlüğü: Subdomain otomatik olarak TenantFilter tarafından
// çözülür (Bölüm 2.2). API'ye ayrıca X-Tenant-ID header'ı gerekmez.
// Sadece cross-origin (farklı domain) isteklerinde X-Tenant-Slug gerekebilir.

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const error = await res.json().catch(() => ({ message: 'Bir hata oluştu' }));
    throw new Error(error.message || `HTTP ${res.status}`);
  }
  return res.json();
}

export async function fetchServices() {
  const res = await fetch(`${API_BASE}/api/public/services`);
  return handleResponse(res);
}

export async function createAppointment(data: AppointmentFormData) {
  const res = await fetch(`${API_BASE}/api/public/appointments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  return handleResponse(res);
}
```

### 11.2 Admin Panel Entegrasyonu

```typescript
// lib/admin-api-client.ts
const MAX_RETRY = 1; // Sonsuz döngüyü önle

export async function adminFetch(
  endpoint: string,
  options?: RequestInit,
  retryCount = 0
) {
  // JWT: httpOnly cookie (SSR güvenli) veya Authorization header
  // localStorage XSS'e açık — httpOnly cookie önerilir
  const token = getAccessToken(); // Cookie'den veya memory'den oku

  const res = await fetch(`${API_BASE}/api/admin${endpoint}`, {
    ...options,
    credentials: 'include',         // httpOnly cookie için gerekli
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
      ...options?.headers,
    },
  });

  // 401 → Token expired, max 1 retry ile refresh dene
  if (res.status === 401 && retryCount < MAX_RETRY) {
    const refreshed = await refreshToken();
    if (refreshed) {
      return adminFetch(endpoint, options, retryCount + 1);
    }
    // Refresh de başarısız → login sayfasına yönlendir
    window.location.href = '/giris';
    throw new Error('Oturum süresi doldu');
  }

  return handleResponse(res);       // Aynı error handling (11.1'deki)
}
```

---

## 12. Tenant Onboarding Akışı

```
Yeni işletme kaydı (tümü @Transactional içinde):

  ① İşletme sahibi kayıt formu doldurur
     → POST /api/platform/tenants
     │
     ▼
  ② Tenant oluşturulur (slug: "guzellik-merkezi-ayse")
     → tenants tablosuna kayıt
     → trial_end_date = now + 14 gün (Bölüm 4 Tenant entity)
     │
     ▼
  ③ Varsayılan admin kullanıcı oluşturulur
     → users tablosuna TENANT_ADMIN rolünde
     → Geçici şifre oluşturulur ve e-posta ile gönderilir
     → İlk girişte şifre değişikliği zorunlu (forcePasswordChange = true)
     │
     ▼
  ④ Varsayılan site ayarları oluşturulur
     → site_settings tablosuna (timezone: Europe/Istanbul default)
     │
     ▼
  ⑤ Varsayılan hizmet kategorileri oluşturulur
     → service_categories tablosuna (işletme tipine göre şablon)
     │
     ▼
  ⑥ Varsayılan çalışma saatleri oluşturulur
     → working_hours tablosuna (Pzt-Cmt 09:00-18:00)
     │
     ▼
  ⑦ Subscription kaydı oluşturulur
     → subscriptions tablosuna (plan = TRIAL, endDate = now + 14 gün)
     │
     ▼
  ⑧ Hoş geldiniz e-postası gönderilir
     → Admin URL + geçici şifre + başlangıç kılavuzu
     │
     ▼
  ⑨ Subdomain aktif: guzellik-merkezi-ayse.app.com
```

---

## 13. Performans Optimizasyonları

### 13.1 N+1 Problemi Çözümü
- `@EntityGraph` ile eager fetch (kontrollü)
- `JOIN FETCH` JPQL sorguları
- `default_batch_fetch_size: 20` (Hibernate)

### 13.2 Sayfalama
- Tüm liste endpoint'leri `Pageable` destekler
- `?page=0&size=20&sort=createdAt,desc`

### 13.3 Cache Stratejisi (Caffeine local cache)
- `tenants`: Tenant slug çözünürlüğü — 5 dk TTL (Bölüm 2.2)
- `services`: Hizmet listesi — 5 dk TTL
- `products`: Ürün listesi — 5 dk TTL
- `blog`: Blog listesi — 5 dk TTL
- `gallery`: Galeri listesi — 5 dk TTL
- Site ayarları: 15 dk TTL
- Müsaitlik: Cache'lenmez (her sorguda güncel veri gerekli)
- Cache invalidation: Entity değiştiğinde `@CacheEvict` (tenant-scoped key ile)
- Redis: Rate limiting ve distributed session için kullanılır, entity cache için değil

### 13.4 Connection Pool
- HikariCP (Spring Boot default)
- `maximum-pool-size: 30` (geliştirme, Bölüm 9.1'de tanımlı)
- Prod: `application-prod.yml`'de tenant sayısına göre 50+ ayarlanır

---

## 14. Monitoring & Logging

### 14.1 Spring Actuator Endpoint'leri
```
/actuator/health         # Sağlık durumu (DB, Redis)
/actuator/metrics        # JVM, HTTP, DB metrikleri
/actuator/prometheus     # Prometheus formatında metrikler
                         # → Gerekli: implementation("io.micrometer:micrometer-registry-prometheus")
                         #   (build.gradle.kts'e eklenecek)
```

### 14.2 Structured Logging (MDC ile)
```kotlin
// TenantFilter'da MDC set edilir — tüm loglara otomatik tenant bilgisi eklenir
import org.slf4j.MDC

// TenantFilter.doFilterInternal() içinde:
MDC.put("tenantId", tenant.id)
MDC.put("tenantSlug", tenant.slug)
try {
    filterChain.doFilter(request, response)
} finally {
    MDC.clear()
}

// DÜZELTME AA-T3: MDC key tutarlılığı — pattern'da %X{tenantSlug} kullanılır.
// application.yml'de log pattern:
// logging.pattern.console: "%d{HH:mm:ss.SSS} [%thread] [tenant=%X{tenantSlug}] %-5level %logger{36} - %msg%n"

// Kullanım — MDC otomatik olarak log satırlarına eklenir:
logger.info("Randevu oluşturuldu: {}", appointmentId)
// Çıktı: 14:30:15.123 [http-1] [tenant=guzellik-merkezi] INFO  AppointmentService - Randevu oluşturuldu: abc-123
```

### 14.3 Audit Log
```kotlin
// DÜZELTME T8: AuditLog neden TenantAwareEntity extend ETMİYOR — Tasarım kararı belgelendi:
// 1. Platform admin tüm tenant'ların loglarını görebilmelidir (cross-tenant görünürlük)
// 2. TenantAwareEntity extend edilse Hibernate filter otomatik tenant_id filtresi uygular
//    → platform admin kendi tenant_id'si olmayan logları göremez
// 3. Bunun yerine manuel tenantId alanı kullanılır + okuma sorgularında:
//    - TENANT_ADMIN: findByTenantId(tenantId) → kendi tenant logları
//    - PLATFORM_ADMIN: findAll() → tüm loglar (filtre yok)
@Entity
@Table(name = "audit_logs")
class AuditLog(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,
    val tenantId: String,
    val userId: String,
    val action: String,          // "CREATE_APPOINTMENT", "UPDATE_SERVICE"
    val entityType: String,      // "Appointment", "Service"  — Kasıtlı tasarım (AA-D7): FK yok,
    val entityId: String,        // entity silindiğinde orphan log kalır — pragmatik yaklaşım (performans + esneklik)
    @Column(columnDefinition = "JSON")                   // DÜZELTME: Hibernate validate JSON ↔ String mapping
    val details: String? = null, // JSON: değişen alanlar
    val ipAddress: String? = null,
    val createdAt: Instant = Instant.now()    // UTC timestamp
)
```

---

## 15. Test Stratejisi

### 15.1 Katmanlı Test

| Katman | Araç | Kapsam |
|--------|------|--------|
| Unit Test | JUnit 5 + MockK | Service katmanı (iş mantığı) |
| Integration Test | Testcontainers (MySQL) | Repository + Service + DB |
| API Test | MockMvc + WebTestClient | Controller endpoint'leri |
| Tenant Isolation | Testcontainers | Tenant A verisi Tenant B'den izole mi? |
| Concurrency Test | CountDownLatch + Threads | Çift randevu engelleme testi |

### 15.2 Kritik Test: Çift Randevu Engelleme

```kotlin
@SpringBootTest
@Testcontainers
class AppointmentConcurrencyTest {

    companion object {
        @Container
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.0")
            .withDatabaseName("aesthetic_saas_test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_turkish_ci")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @Autowired
    private lateinit var appointmentService: AppointmentService

    @Autowired
    private lateinit var appointmentRepository: AppointmentRepository

    private val testTenantId = "test-tenant-001"

    @BeforeEach
    fun setup() {
        // Multi-tenant test: TenantContext ayarla
        TenantContext.setTenantId(testTenantId)
        // Test verisi oluştur (staff, service, working_hours vb.)
    }

    @AfterEach
    fun cleanup() {
        TenantContext.clear()
        appointmentRepository.deleteAll()
    }

    @Test
    fun `aynı slota eşzamanlı iki randevu isteği geldiğinde sadece biri başarılı olmalı`() {
        val request1 = CreateAppointmentRequest(/* aynı tarih, saat, staff */)
        val request2 = CreateAppointmentRequest(/* aynı tarih, saat, staff */)

        val latch = CountDownLatch(1)
        val results = ConcurrentHashMap<String, Boolean>()

        // Not: Her thread kendi TenantContext'ini set etmeli
        val thread1 = Thread {
            TenantContext.setTenantId(testTenantId)
            latch.await()
            try {
                appointmentService.createAppointment(request1)
                results["thread1"] = true
            } catch (e: AppointmentConflictException) {
                results["thread1"] = false
            } finally {
                TenantContext.clear()
            }
        }

        val thread2 = Thread {
            TenantContext.setTenantId(testTenantId)
            latch.await()
            try {
                appointmentService.createAppointment(request2) // aynı slot
                results["thread2"] = true
            } catch (e: AppointmentConflictException) {
                results["thread2"] = false
            } finally {
                TenantContext.clear()
            }
        }

        thread1.start()
        thread2.start()
        latch.countDown() // İkisini aynı anda başlat

        thread1.join(5000) // 5s timeout
        thread2.join(5000)

        // Sadece biri başarılı olmalı
        val successCount = results.values.count { it }
        assertEquals(1, successCount,
            "Aynı slota iki randevu oluşturulmamalı!")
    }
}
```

---

## 16. Raporlama & İstatistik

> **Detaylı implementasyon §22 (DashboardService, Reporting & Analytics) ve §28.10'a (Mimari Notlar) taşınmıştır.**
> Bu bölümün önceki placeholder içeriği artık somut kodla §22'de yer almaktadır.

---

## 17. Ödeme & Abonelik Altyapısı

Tenant'ların plan bazlı ödeme yapabilmesi için. Türkiye pazarı için **iyzico** (PayTR alternatif), uluslararası için **Stripe**.

### 17.1 Entity'ler

```kotlin
@Entity
@Table(name = "subscriptions")
class Subscription : TenantAwareEntity() {             // DÜZELTME: TenantAwareEntity extend
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null
    // tenantId → TenantAwareEntity'den miras alınır

    @Enumerated(EnumType.STRING)
    var plan: SubscriptionPlan = SubscriptionPlan.TRIAL

    // Not: startDate/endDate service katmanında set edilmeli,
    // entity default'ları sadece constructor zamanı çalışır.
    var startDate: LocalDate = LocalDate.now()
    var endDate: LocalDate = LocalDate.now().plusDays(14)
    var autoRenew: Boolean = true

    // Plan limitleri (plan seçimi sırasında service tarafından set edilir)
    // DÜZELTME AA-D4: null = sınırsız (ENTERPRISE). Plan limit kontrolünde: if (limit == null) return // sınırsız
    var maxStaff: Int? = 1                // TRIAL: 1, STARTER: 1, PRO: 5, BUS: 15, ENT: null (sınırsız)
    var maxAppointmentsPerMonth: Int? = 100 // TRIAL: 100, STARTER: 100, PRO: 500, BUS: 2000, ENT: null (sınırsız)
    var maxStorageMb: Int = 500           // Dosya yükleme limiti (MB)

    @Enumerated(EnumType.STRING)
    var status: SubscriptionStatus = SubscriptionStatus.ACTIVE

    // Modül sistemi — SubscriptionModule entity ile yönetilir (relational)
    // NOT AA-O1: enabledModules JSON alanı KULLANILMAZ — SubscriptionModule tercih edilir.
    // Tenant entity'deki Tenant.enabledModules alanı da kullanılmaz; modül erişimi
    // tamamen SubscriptionModule relational entity üzerinden kontrol edilir.
    // Core modüller (randevu, müşteri yönetimi, bildirim, tema) HER pakette bulunur.
    @OneToMany(mappedBy = "subscription", fetch = FetchType.LAZY, cascade = [CascadeType.ALL])
    val modules: MutableList<SubscriptionModule> = mutableListOf()

    // Faturalama
    @Column(precision = 10, scale = 2)
    var monthlyPrice: BigDecimal = BigDecimal.ZERO   // Toplam aylık fiyat (paket + modüller)

    @Enumerated(EnumType.STRING)
    var billingPeriod: BillingPeriod = BillingPeriod.MONTHLY

    @CreationTimestamp val createdAt: Instant? = null   // DÜZELTME: LocalDateTime → Instant (UTC)
    @UpdateTimestamp var updatedAt: Instant? = null
}

enum class BillingPeriod {
    MONTHLY,            // Aylık faturalama
    YEARLY              // Yıllık faturalama (%20 indirim)
}

@Entity
@Table(name = "payments")
class Payment : TenantAwareEntity() {                   // DÜZELTME: TenantAwareEntity extend
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @ManyToOne(fetch = FetchType.LAZY)                  // DÜZELTME: String → JPA ilişki
    @JoinColumn(name = "subscription_id")
    var subscription: Subscription? = null

    @Column(precision = 10, scale = 2)
    var amount: BigDecimal = BigDecimal.ZERO
    var currency: String = "TRY"

    @Enumerated(EnumType.STRING)
    var status: PaymentStatus = PaymentStatus.PENDING

    @Enumerated(EnumType.STRING)                        // DÜZELTME: String → enum
    var provider: PaymentProvider = PaymentProvider.IYZICO
    var providerPaymentId: String? = null                // iyzico/stripe payment ID
    var providerSubscriptionId: String? = null           // Tekrarlayan ödeme ID

    var failureReason: String? = null
    var paidAt: Instant? = null                          // DÜZELTME: LocalDateTime → Instant

    @CreationTimestamp val createdAt: Instant? = null    // DÜZELTME: LocalDateTime → Instant
}

@Entity
@Table(name = "invoices")
class Invoice : TenantAwareEntity() {                   // DÜZELTME: TenantAwareEntity extend
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    var payment: Payment? = null

    var invoiceNumber: String = ""            // "INV-2026-0001"
    @Column(precision = 10, scale = 2)
    var totalAmount: BigDecimal = BigDecimal.ZERO    // DÜZELTME T9: amount → totalAmount (V12 DDL ile tutarlı)
    var currency: String = "TRY"
    @Column(precision = 10, scale = 2)
    var taxAmount: BigDecimal = BigDecimal.ZERO
    var taxRate: Int = 20                     // %20 KDV

    var billingName: String = ""
    var billingAddress: String = ""
    var taxId: String? = null                 // Vergi numarası

    @CreationTimestamp val createdAt: Instant? = null    // DÜZELTME: LocalDateTime → Instant
}

enum class SubscriptionStatus { ACTIVE, PAST_DUE, CANCELLED, EXPIRED }
enum class PaymentStatus { PENDING, COMPLETED, FAILED, REFUNDED }
enum class PaymentProvider { IYZICO, STRIPE }            // DÜZELTME: String yerine enum
```

### 17.2 Plan Limitleri Kontrolü

```kotlin
@Service
class PlanLimitService(
    private val subscriptionRepository: SubscriptionRepository,
    private val userRepository: UserRepository,
    private val appointmentRepository: AppointmentRepository,
    private val siteSettingsRepository: SiteSettingsRepository   // DÜZELTME: Tenant timezone için
) {
    fun checkCanAddStaff(tenantId: String) {
        val subscription = getActiveSubscription(tenantId)
        val currentStaffCount = userRepository.countByTenantIdAndRole(tenantId, Role.STAFF)
        if (currentStaffCount >= subscription.maxStaff) {
            throw PlanLimitExceededException(
                "Mevcut planınız (${subscription.plan}) en fazla ${subscription.maxStaff} personele izin veriyor. " +
                "Planınızı yükseltin."
            )
        }
    }

    fun checkCanCreateAppointment(tenantId: String) {
        val subscription = getActiveSubscription(tenantId)

        // DÜZELTME: Tenant timezone ile ayın başını hesapla
        val settings = siteSettingsRepository.findByTenantId(tenantId)
        val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")
        val monthStart = ZonedDateTime.now(tenantZone)
            .withDayOfMonth(1)
            .toLocalDate()
            .atStartOfDay(tenantZone)                            // ZonedDateTime olarak 00:00:00
            .toInstant()                                          // DÜZELTME: Instant'a çevir (createdAt: Instant)

        val monthlyCount = appointmentRepository.countByTenantIdAndCreatedAtAfter(
            tenantId, monthStart
        )
        if (monthlyCount >= subscription.maxAppointmentsPerMonth) {
            throw PlanLimitExceededException(
                "Bu ay ${subscription.maxAppointmentsPerMonth} randevu limitine ulaştınız. " +
                "Planınızı yükseltin."
            )
        }
    }

    private fun getActiveSubscription(tenantId: String): Subscription {
        return subscriptionRepository.findByTenantIdAndStatus(tenantId, SubscriptionStatus.ACTIVE)
            ?: throw PlanLimitExceededException("Aktif abonelik bulunamadı. Lütfen bir plan satın alın.")
    }
}
```

### 17.3 Modül Sistemi (Feature Flags)

Farklı sektörler farklı modüllere ihtiyaç duyar. Bir berber ürün satışı istemez, bir diş hekimi blog istemez. Her tenant ihtiyacına göre modülleri açıp kapayabilir.

**Core Modüller (HER pakette, kapatılamaz):**
- Randevu sistemi
- Müşteri yönetimi (CRUD + notlar)
- Çalışma saatleri ayarı
- Temel bildirimler (email)
- Tema özelleştirme (logo, renk)
- Temel raporlar

**Opsiyonel Modüller (add-on olarak satın alınır):**

```kotlin
enum class FeatureModule {
    BLOG,                   // Blog yazıları, SEO, kategoriler
    PRODUCTS,               // Ürün kataloğu, stok takibi
    GALLERY,                // Önce/sonra görselleri
    PATIENT_RECORDS,        // Hasta/danışan kaydı, tedavi geçmişi, dosya ekleri
    ADVANCED_REPORTS,       // Detaylı analitik, gelir raporları, Excel/PDF export
    SMS_NOTIFICATIONS,      // SMS hatırlatma (kullanım başına ücretli)
    CUSTOM_DOMAIN,          // Özel domain desteği (salon.com)
    REVIEWS                 // Müşteri değerlendirmeleri, puanlama
}
```

**SubscriptionModule Entity:**

```kotlin
@Entity
@Table(
    name = "subscription_modules",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_sub_module",
            columnNames = ["subscription_id", "module_key"]
        )
    ]
)
class SubscriptionModule : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    lateinit var subscription: Subscription

    @Enumerated(EnumType.STRING)
    @Column(name = "module_key", nullable = false)
    var moduleKey: FeatureModule = FeatureModule.BLOG

    var isEnabled: Boolean = true

    @Column(precision = 10, scale = 2)
    var monthlyPrice: BigDecimal = BigDecimal.ZERO    // Bu modülün aylık ücreti

    var activatedAt: Instant = Instant.now()
    var deactivatedAt: Instant? = null

    @CreationTimestamp val createdAt: Instant? = null
    @UpdateTimestamp var updatedAt: Instant? = null
}
```

**ModuleAccessService — Modül erişim kontrolü:**

```kotlin
@Service
class ModuleAccessService(
    private val subscriptionModuleRepository: SubscriptionModuleRepository,
    private val subscriptionRepository: SubscriptionRepository
) {
    /**
     * Tenant'ın belirtilen modüle erişimi var mı kontrol eder.
     * TRIAL plan: Tüm modüller açık (değerlendirme amaçlı).
     * Diğer planlar: SubscriptionModule tablosunda aktif kayıt olmalı.
     */
    fun hasAccess(tenantId: String, module: FeatureModule): Boolean {
        val subscription = subscriptionRepository
            .findByTenantIdAndStatus(tenantId, SubscriptionStatus.ACTIVE)
            ?: return false

        // TRIAL — tüm modüller açık (değerlendirme süresi)
        if (subscription.plan == SubscriptionPlan.TRIAL) return true

        return subscriptionModuleRepository
            .existsBySubscriptionIdAndModuleKeyAndIsEnabledTrue(subscription.id!!, module)
    }

    /**
     * Modül erişimi yoksa PlanLimitExceededException fırlatır.
     * API controller'larında çağrılır.
     */
    fun requireAccess(tenantId: String, module: FeatureModule) {
        if (!hasAccess(tenantId, module)) {
            throw PlanLimitExceededException(
                "Bu özellik (${module.name}) mevcut planınıza dahil değildir. " +
                "Modülü eklemek için abonelik ayarlarınızı güncelleyin."
            )
        }
    }

    /**
     * Tenant'ın aktif modüllerinin listesini döndürür.
     */
    fun getEnabledModules(tenantId: String): Set<FeatureModule> {
        val subscription = subscriptionRepository
            .findByTenantIdAndStatus(tenantId, SubscriptionStatus.ACTIVE)
            ?: return emptySet()

        // TRIAL — tüm modüller
        if (subscription.plan == SubscriptionPlan.TRIAL) {
            return FeatureModule.entries.toSet()
        }

        return subscriptionModuleRepository
            .findBySubscriptionIdAndIsEnabledTrue(subscription.id!!)
            .map { it.moduleKey }
            .toSet()
    }
}
```

**SubscriptionModuleRepository:**

```kotlin
interface SubscriptionModuleRepository : JpaRepository<SubscriptionModule, String> {
    fun existsBySubscriptionIdAndModuleKeyAndIsEnabledTrue(
        subscriptionId: String, moduleKey: FeatureModule
    ): Boolean

    fun findBySubscriptionIdAndIsEnabledTrue(
        subscriptionId: String
    ): List<SubscriptionModule>

    fun findBySubscriptionId(subscriptionId: String): List<SubscriptionModule>
}
```

### 17.4 Abonelik Paketleri & Fiyatlandırma

**"Temel Paket + Modül Add-on" modeli:** Her tenant bir temel paket seçer (personel/randevu limiti), üstüne ihtiyaç duyduğu modülleri add-on olarak ekler.

**A) Temel Paketler (Core — herkes alır):**

| Paket | Personel | Randevu/ay | Depolama | Fiyat (örnek) |
|-------|----------|------------|----------|---------------|
| STARTER | 1 kişi | 100 | 500MB | 199 TL/ay |
| PROFESSIONAL | 5 kişi | 500 | 2GB | 499 TL/ay |
| BUSINESS | 15 kişi | 2.000 | 5GB | 999 TL/ay |
| ENTERPRISE | Sınırsız | Sınırsız | 20GB | Özel fiyat |

**B) Modül Add-on'lar (opsiyonel):**

| Modül | Açıklama | Fiyat (örnek) |
|-------|----------|---------------|
| BLOG | Blog yazıları, SEO, kategoriler | +99 TL/ay |
| PRODUCTS | Ürün kataloğu, stok takibi | +149 TL/ay |
| GALLERY | Önce/sonra görselleri | +49 TL/ay |
| PATIENT_RECORDS | Tedavi geçmişi, tıbbi notlar, dosya ekleri | +199 TL/ay |
| ADVANCED_REPORTS | Detaylı analitik, gelir raporları | +99 TL/ay |
| SMS_NOTIFICATIONS | SMS hatırlatma (kullanım başına) | 0.15 TL/SMS |
| CUSTOM_DOMAIN | Özel domain (salon.com) | +49 TL/ay |
| REVIEWS | Müşteri yorumları, puanlama | +49 TL/ay |

**C) Sektör Paketleri (hazır kombinasyonlar, indirimli):**

| Sektör Paketi | İçerik | İndirimli Fiyat |
|---------------|--------|-----------------|
| Kuaför/Berber | STARTER + GALLERY | 229 TL/ay |
| Güzellik Kliniği | PROFESSIONAL + BLOG + GALLERY + REVIEWS | 649 TL/ay |
| Diş Kliniği | PROFESSIONAL + PATIENT_RECORDS + GALLERY | 699 TL/ay |
| Fizyoterapi | PROFESSIONAL + PATIENT_RECORDS | 649 TL/ay |
| Masaj Salonu | STARTER + GALLERY + REVIEWS | 279 TL/ay |
| Kuaför Salonu | STARTER + GALLERY + PRODUCTS | 279 TL/ay |
| Diyetisyen | PROFESSIONAL + PATIENT_RECORDS | 649 TL/ay |
| Veteriner | PROFESSIONAL + PATIENT_RECORDS + GALLERY | 699 TL/ay |
| Genel | STARTER (sadece core) | 199 TL/ay |

```kotlin
// Sektör paketleri — önceden tanımlanmış modül kombinasyonları
enum class IndustryBundle(
    val plan: SubscriptionPlan,
    val modules: Set<FeatureModule>,
    val monthlyPrice: BigDecimal
) {
    BARBER_BUNDLE(
        SubscriptionPlan.STARTER,
        setOf(FeatureModule.GALLERY),
        BigDecimal("229.00")
    ),
    BEAUTY_CLINIC_BUNDLE(
        SubscriptionPlan.PROFESSIONAL,
        setOf(FeatureModule.BLOG, FeatureModule.GALLERY, FeatureModule.REVIEWS),
        BigDecimal("649.00")
    ),
    DENTAL_CLINIC_BUNDLE(
        SubscriptionPlan.PROFESSIONAL,
        setOf(FeatureModule.PATIENT_RECORDS, FeatureModule.GALLERY),
        BigDecimal("699.00")
    ),
    PHYSIOTHERAPY_BUNDLE(
        SubscriptionPlan.PROFESSIONAL,
        setOf(FeatureModule.PATIENT_RECORDS),
        BigDecimal("649.00")
    ),
    MASSAGE_SALON_BUNDLE(
        SubscriptionPlan.STARTER,
        setOf(FeatureModule.GALLERY, FeatureModule.REVIEWS),
        BigDecimal("279.00")
    ),
    HAIR_SALON_BUNDLE(
        SubscriptionPlan.STARTER,
        setOf(FeatureModule.GALLERY, FeatureModule.PRODUCTS),
        BigDecimal("279.00")
    ),
    DIETITIAN_BUNDLE(
        SubscriptionPlan.PROFESSIONAL,
        setOf(FeatureModule.PATIENT_RECORDS),
        BigDecimal("649.00")
    ),
    VETERINARY_BUNDLE(
        SubscriptionPlan.PROFESSIONAL,
        setOf(FeatureModule.PATIENT_RECORDS, FeatureModule.GALLERY),
        BigDecimal("699.00")
    ),
    GENERAL_BUNDLE(
        SubscriptionPlan.STARTER,
        emptySet(),                                       // Modül yok — sadece core özellikler
        BigDecimal("199.00")
    )
}
```

### 17.5 iyzico Entegrasyonu (Türkiye)

```kotlin
// API Endpoint'leri:
// POST /api/admin/billing/subscribe         → Plan seçimi + ödeme başlatma
// POST /api/webhooks/iyzico                 → iyzico callback (auth YOK — dış servis erişir)
//                                              DÜZELTME: /api/admin/ altından çıkarıldı
// GET  /api/admin/billing/invoices          → Fatura listesi
// GET  /api/admin/billing/current-plan      → Mevcut plan + kullanım istatistikleri
// POST /api/admin/billing/cancel            → Abonelik iptali
// POST /api/admin/billing/upgrade           → Plan yükseltme

// Not: iyzico dependency zaten build.gradle.kts'te mevcut (Bölüm 9.2)
// implementation("com.iyzipay:iyzipay-java:2.0.131")

// DÜZELTME: iyzico webhook HMAC doğrulama — dış servislerden gelen isteklerin güvenliği
@RestController
@RequestMapping("/api/webhooks")
class WebhookController(
    private val paymentService: PaymentService,   // DÜZELTME: BillingService → PaymentService (tanımlı sınıf)
    @Value("\${iyzico.secret-key}") private val iyzicoSecretKey: String
) {
    private val logger = LoggerFactory.getLogger(WebhookController::class.java)

    @PostMapping("/iyzico")
    fun handleIyzicoWebhook(
        @RequestBody payload: String,
        @RequestHeader("X-IYZ-SIGNATURE") signature: String?
    ): ResponseEntity<String> {
        // 1. HMAC-SHA256 imza doğrulama
        if (signature == null || !verifySignature(payload, signature)) {
            logger.warn("iyzico webhook — geçersiz imza reddedildi")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature")
        }

        // 2. İş mantığını işle
        try {
            paymentService.processWebhookPayload(payload)
        } catch (e: Exception) {
            logger.error("iyzico webhook işleme hatası", e)
            // Webhook'a 200 dön (tekrar denemesini engelle), hatayı logla
        }

        return ResponseEntity.ok("OK")
    }

    private fun verifySignature(payload: String, receivedSignature: String): Boolean {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(iyzicoSecretKey.toByteArray(), "HmacSHA256"))
        val expectedSignature = Base64.getEncoder().encodeToString(mac.doFinal(payload.toByteArray()))
        return MessageDigest.isEqual(expectedSignature.toByteArray(), receivedSignature.toByteArray())
    }
}
```

---

## 18. Bildirim Altyapısı

### 18.1 Bildirim Servisi Mimarisi

```
                         ┌─────────────────┐
                         │ NotificationSvc  │
                         │  (async)         │
                         └────────┬─────────┘
                                  │
                    ┌─────────────┼─────────────┐
                    │             │             │
                    ▼             ▼             ▼
              ┌──────────┐ ┌──────────┐ ┌────────────┐
              │  E-posta  │ │   SMS    │ │   Push     │
              │ SendGrid  │ │ Netgsm   │ │ (gelecek)  │
              └──────────┘ └──────────┘ └────────────┘
```

### 18.2 Bildirim Entity + Template

```kotlin
@Entity
@Table(
    name = "notification_templates",
    uniqueConstraints = [                                // DÜZELTME: Tenant+type unique
        UniqueConstraint(columnNames = ["tenant_id", "type"])
    ]
)
class NotificationTemplate : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    @Enumerated(EnumType.STRING)
    var type: NotificationType = NotificationType.APPOINTMENT_CONFIRMATION

    var emailSubject: String? = null          // "Randevunuz onaylandı — {{serviceName}}"
    @Column(columnDefinition = "TEXT")
    var emailBody: String? = null             // HTML template (Mustache syntax)
    @Column(length = 320)                     // DÜZELTME: SMS uzunluk sınırı (multi-part: 2×160)
    var smsBody: String? = null

    var isEmailEnabled: Boolean = true
    var isSmsEnabled: Boolean = false          // SMS ek maliyet, opsiyonel
}

@Entity
@Table(name = "notification_logs")
class NotificationLog : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    var appointmentId: String? = null          // DÜZELTME: Hangi randevuya ait olduğu
    var recipientEmail: String? = null
    var recipientPhone: String? = null

    @Enumerated(EnumType.STRING)
    var channel: NotificationChannel = NotificationChannel.EMAIL

    @Enumerated(EnumType.STRING)
    var type: NotificationType = NotificationType.APPOINTMENT_CONFIRMATION

    @Enumerated(EnumType.STRING)
    var status: DeliveryStatus = DeliveryStatus.PENDING

    var errorMessage: String? = null
    var retryCount: Int = 0
    var sentAt: Instant? = null                // DÜZELTME: LocalDateTime → Instant (UTC)
    @CreationTimestamp val createdAt: Instant? = null   // DÜZELTME: LocalDateTime → Instant
}

enum class NotificationType {
    APPOINTMENT_CONFIRMATION,         // Randevu onaylandı
    APPOINTMENT_REMINDER_24H,         // 24 saat kala hatırlatma
    APPOINTMENT_REMINDER_1H,          // 1 saat kala hatırlatma
    APPOINTMENT_CANCELLED,            // Randevu iptal edildi
    APPOINTMENT_RESCHEDULED,          // Randevu zamanı değişti
    WELCOME,                          // Yeni kayıt hoşgeldin
    PASSWORD_RESET,                   // Şifre sıfırlama
    REVIEW_REQUEST,                   // Randevu sonrası değerlendirme isteği
    APPOINTMENT_NO_SHOW,              // Müşteri gelmedi (TENANT_ADMIN'a bildirim)
    CLIENT_BLACKLISTED_NOTICE         // Müşteri kara listeye alındı
}
enum class NotificationChannel { EMAIL, SMS }
enum class DeliveryStatus { PENDING, SENT, FAILED, BOUNCED }

/**
 * Tüm özel exception sınıfları — proje yapısında exception/ paketi altında bulunur.
 * GlobalExceptionHandler (§7.8) her birini ayrı HTTP durum koduyla eşleştirir.
 */

// Auth & Security
class UnauthorizedException(message: String) : RuntimeException(message)                        // 401
class AccountLockedException(message: String) : RuntimeException(message)                       // 423

// Resource
class ResourceNotFoundException(message: String) : RuntimeException(message)                    // 404
class TenantNotFoundException(message: String) : RuntimeException(message)                      // 404
class DuplicateResourceException(message: String) : RuntimeException(message)                   // 409

// Appointment
class AppointmentConflictException(message: String) : RuntimeException(message)                 // 409

// Payment
class PaymentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)  // 500/502

// Notification
class NotificationDeliveryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// Plan
class PlanLimitExceededException(message: String) : RuntimeException(message)                   // 429

// Blacklist
class ClientBlacklistedException(message: String) : RuntimeException(message)                   // 403
```

### 18.2.1 NotificationService — Bildirim Gönderim Servisi

```kotlin
/**
 * DÜZELTME: @Async metotları doğrudan JPA entity almamalı (LazyInitializationException riski).
 * Bunun yerine DTO kullanılır. Caller tarafında Hibernate.initialize() veya @EntityGraph ile
 * lazy alanlar yüklenir, ardından DTO'ya dönüştürülür.
 */
data class NotificationContext(
    val appointmentId: String,
    val tenantId: String,
    val clientName: String,
    val clientEmail: String,
    val clientPhone: String,
    val serviceName: String,
    val staffName: String,
    val date: String,
    val startTime: String
)

@Service
class NotificationService(
    private val notificationTemplateRepository: NotificationTemplateRepository,
    private val notificationLogRepository: NotificationLogRepository,
    private val emailService: EmailService,
    private val smsService: SmsService
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    /** Appointment entity'den DTO oluştur — CALLER (transactional context) tarafından çağrılmalı */
    fun toContext(appointment: Appointment): NotificationContext {
        return NotificationContext(
            appointmentId = appointment.id!!,
            tenantId = TenantContext.getTenantId(),
            clientName = appointment.clientName,
            clientEmail = appointment.clientEmail,
            clientPhone = appointment.clientPhone,
            serviceName = appointment.primaryService?.title ?: "",   // Lazy alan burada yüklenir
            staffName = appointment.staff?.name ?: "",               // Lazy alan burada yüklenir
            date = appointment.date.toString(),
            startTime = appointment.startTime.toString()
        )
    }

    /**
     * Randevu onay bildirimi gönder
     * DÜZELTME: Appointment yerine NotificationContext (DTO) alır — @Async thread'de lazy loading güvenli
     */
    @Async("taskExecutor")
    @Retryable(value = [NotificationDeliveryException::class], maxAttempts = 3, backoff = Backoff(delay = 2000))
    fun sendAppointmentConfirmation(ctx: NotificationContext) {
        val template = notificationTemplateRepository
            .findByTenantIdAndType(ctx.tenantId, NotificationType.APPOINTMENT_CONFIRMATION)
            ?: return

        val variables = mapOf(
            "clientName" to ctx.clientName,
            "serviceName" to ctx.serviceName,
            "date" to ctx.date,
            "startTime" to ctx.startTime,
            "staffName" to ctx.staffName
        )

        sendNotification(ctx, template, variables)
    }

    /**
     * DÜZELTME: Eksik sendAppointmentCancellation metodu eklendi (cancelAppointment'tan çağrılıyor)
     */
    @Async("taskExecutor")
    @Retryable(value = [NotificationDeliveryException::class], maxAttempts = 3, backoff = Backoff(delay = 2000))
    fun sendAppointmentCancellation(ctx: NotificationContext) {
        val template = notificationTemplateRepository
            .findByTenantIdAndType(ctx.tenantId, NotificationType.APPOINTMENT_CANCELLED)
            ?: return

        val variables = mapOf(
            "clientName" to ctx.clientName,
            "serviceName" to ctx.serviceName,
            "date" to ctx.date,
            "startTime" to ctx.startTime
        )

        sendNotification(ctx, template, variables)
    }

    /**
     * Hatırlatma bildirimi gönder (scheduled job tarafından çağrılır)
     */
    @Retryable(value = [NotificationDeliveryException::class], maxAttempts = 3, backoff = Backoff(delay = 2000))
    fun sendReminder(ctx: NotificationContext, type: NotificationType) {
        val template = notificationTemplateRepository
            .findByTenantIdAndType(ctx.tenantId, type) ?: return

        val variables = mapOf(
            "clientName" to ctx.clientName,
            "serviceName" to ctx.serviceName,
            "date" to ctx.date,
            "startTime" to ctx.startTime
        )

        sendNotification(ctx, template, variables)
    }

    /**
     * Randevu sonrası değerlendirme isteği gönder
     */
    @Async("taskExecutor")
    @Retryable(value = [NotificationDeliveryException::class], maxAttempts = 3, backoff = Backoff(delay = 2000))
    fun sendReviewRequest(ctx: NotificationContext) {
        val template = notificationTemplateRepository
            .findByTenantIdAndType(ctx.tenantId, NotificationType.REVIEW_REQUEST) ?: return

        val variables = mapOf(
            "clientName" to ctx.clientName,
            "serviceName" to ctx.serviceName
        )

        sendNotification(ctx, template, variables)
    }

    /**
     * No-show bildirimi — TENANT_ADMIN'a "müşteri randevuya gelmedi" bilgisi gönderir.
     * DÜZELTME: Appointment yerine NotificationContext (DTO) alır — @Async thread'de lazy loading güvenli
     */
    @Async("taskExecutor")
    fun sendNoShowNotification(ctx: NotificationContext) {
        val template = notificationTemplateRepository
            .findByTenantIdAndType(ctx.tenantId, NotificationType.APPOINTMENT_NO_SHOW) ?: return

        val variables = mapOf(
            "clientName" to ctx.clientName,
            "serviceName" to ctx.serviceName,
            "date" to ctx.date,
            "time" to ctx.startTime
        )
        sendNotification(ctx, template, variables)
    }

    /**
     * Kara liste bildirimi — TENANT_ADMIN'a "müşteri kara listeye alındı" bilgisi gönderir.
     * DÜZELTME: User entity yerine DTO bazlı çağrı — @Async thread'de lazy loading güvenli.
     * Caller tarafında toBlacklistContext() ile DTO oluşturulmalı.
     */
    @Async("taskExecutor")
    fun sendClientBlacklistedNotification(ctx: NotificationContext, noShowCount: Int) {
        val template = notificationTemplateRepository
            .findByTenantIdAndType(ctx.tenantId, NotificationType.CLIENT_BLACKLISTED_NOTICE) ?: return

        val variables = mapOf(
            "clientName" to ctx.clientName,
            "clientEmail" to ctx.clientEmail,
            "noShowCount" to noShowCount.toString()
        )

        // TENANT_ADMIN'a bildirim gönder
        sendNotification(ctx, template, variables)
    }

    /** User entity'den kara liste bildirimi için DTO oluştur — CALLER (transactional context) tarafından çağrılmalı */
    fun toBlacklistContext(client: User): NotificationContext {
        return NotificationContext(
            appointmentId = "",       // Kara liste bildirimi randevuya bağlı değil
            tenantId = client.tenantId,
            clientName = client.name,
            clientEmail = client.email,
            clientPhone = client.phone ?: "",
            serviceName = "",
            staffName = "",
            date = "",
            startTime = ""
        )
    }

    /**
     * DÜZELTME: Exception'lar artık re-throw ediliyor → @Retryable tetiklenebilir.
     * Başarısız gönderimler loglanır, ardından NotificationDeliveryException fırlatılır.
     */
    private fun sendNotification(
        ctx: NotificationContext,
        template: NotificationTemplate,
        variables: Map<String, String>
    ) {
        // E-posta gönderimi
        if (template.isEmailEnabled && ctx.clientEmail.isNotBlank()) {
            try {
                val subject = replaceVariables(template.emailSubject ?: "", variables)
                val body = replaceVariables(template.emailBody ?: "", variables)
                emailService.send(ctx.clientEmail, subject, body)
                logNotification(ctx, NotificationChannel.EMAIL, template.type, DeliveryStatus.SENT)
            } catch (e: Exception) {
                logger.error("E-posta gönderilemedi — appointment={}, email={}", ctx.appointmentId, ctx.clientEmail, e)
                logNotification(ctx, NotificationChannel.EMAIL, template.type, DeliveryStatus.FAILED, e.message)
                throw NotificationDeliveryException("E-posta gönderilemedi: ${ctx.clientEmail}", e)
            }
        }

        // SMS gönderimi
        if (template.isSmsEnabled && ctx.clientPhone.isNotBlank()) {
            try {
                val body = replaceVariables(template.smsBody ?: "", variables)
                smsService.send(ctx.clientPhone, body)
                logNotification(ctx, NotificationChannel.SMS, template.type, DeliveryStatus.SENT)
            } catch (e: Exception) {
                logger.error("SMS gönderilemedi — appointment={}, phone={}", ctx.appointmentId, ctx.clientPhone, e)
                logNotification(ctx, NotificationChannel.SMS, template.type, DeliveryStatus.FAILED, e.message)
                throw NotificationDeliveryException("SMS gönderilemedi: ${ctx.clientPhone}", e)
            }
        }
    }

    private fun logNotification(
        ctx: NotificationContext,
        channel: NotificationChannel, type: NotificationType,
        status: DeliveryStatus, errorMessage: String? = null
    ) {
        val log = NotificationLog().apply {
            this.appointmentId = ctx.appointmentId
            this.recipientEmail = if (channel == NotificationChannel.EMAIL) ctx.clientEmail else null
            this.recipientPhone = if (channel == NotificationChannel.SMS) ctx.clientPhone else null
            this.channel = channel
            this.type = type
            this.status = status
            this.errorMessage = errorMessage
            if (status == DeliveryStatus.SENT) this.sentAt = Instant.now()
        }
        notificationLogRepository.save(log)
    }

    /** Mustache-style {{variable}} değişken yerleştirme */
    // DÜZELTME AA-D5: XSS koruması — user input HTML escape edilmeli (email body HTML)
    private fun replaceVariables(template: String, variables: Map<String, String>): String {
        var result = template
        variables.forEach { (key, value) ->
            val safeValue = org.springframework.web.util.HtmlUtils.htmlEscape(value)
            result = result.replace("{{$key}}", safeValue)
        }
        return result
    }
}
```

### 18.3 Randevu Hatırlatıcı Job

```kotlin
@Component
class AppointmentReminderJob(
    private val tenantAwareScheduler: TenantAwareScheduler,
    private val appointmentRepository: AppointmentRepository,
    private val notificationService: NotificationService,
    private val siteSettingsRepository: SiteSettingsRepository    // DÜZELTME: Tenant timezone
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AppointmentReminderJob::class.java)
    }

    // Her 5 dakikada çalışır
    @Scheduled(fixedRate = 300_000)
    @SchedulerLock(name = "send24HourReminders", lockAtLeastFor = "4m", lockAtMostFor = "10m")
    fun send24HourReminders() {
        tenantAwareScheduler.executeForAllTenants { tenant ->
            // DÜZELTME: Tenant timezone ile "24 saat sonra" hesapla
            val settings = siteSettingsRepository.findByTenantId(tenant.id!!)
            val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")
            val tomorrow = ZonedDateTime.now(tenantZone).plusHours(24)

            // DÜZELTME: Repository'de reminder24hSent=false filtresi
            val appointments = appointmentRepository
                .findUpcomingNotReminded(tomorrow.toLocalDate(), tomorrow.toLocalTime(), reminder24hSent = false)

            val updated = mutableListOf<Appointment>()
            appointments.forEach { appointment ->
                try {                                            // DÜZELTME: Per-appointment error handling
                    notificationService.sendReminder(notificationService.toContext(appointment), NotificationType.APPOINTMENT_REMINDER_24H)
                    appointment.reminder24hSent = true
                    updated.add(appointment)
                } catch (e: Exception) {
                    logger.error("24h hatırlatma gönderilemedi — appointment={}", appointment.id, e)
                }
            }
            if (updated.isNotEmpty()) {
                appointmentRepository.saveAll(updated)           // DÜZELTME: Batch save
            }
        }
    }

    @Scheduled(fixedRate = 300_000)
    fun send1HourReminders() {
        tenantAwareScheduler.executeForAllTenants { tenant ->
            val settings = siteSettingsRepository.findByTenantId(tenant.id!!)
            val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")
            val oneHourLater = ZonedDateTime.now(tenantZone).plusHours(1)

            // DÜZELTME: Repository'de iki flag birden filtrele (24h gönderilmiş, 1h gönderilmemiş)
            val appointments = appointmentRepository
                .findUpcomingNotReminded(oneHourLater.toLocalDate(), oneHourLater.toLocalTime(),
                    reminder24hSent = true, reminder1hSent = false)

            val updated = mutableListOf<Appointment>()
            appointments.forEach { appointment ->
                try {
                    notificationService.sendReminder(notificationService.toContext(appointment), NotificationType.APPOINTMENT_REMINDER_1H)
                    appointment.reminder1hSent = true
                    updated.add(appointment)
                } catch (e: Exception) {
                    logger.error("1h hatırlatma gönderilemedi — appointment={}", appointment.id, e)
                }
            }
            if (updated.isNotEmpty()) {
                appointmentRepository.saveAll(updated)
            }
        }
    }
}
```

### 18.4 Bildirim Provider Konfigürasyonu

> **build.gradle.kts dependency'leri:**
> - E-posta: `implementation("com.sendgrid:sendgrid-java:4.10.2")`
> - SMS (Netgsm): REST API kullanır, ek dependency gerekmez (`RestTemplate`/`WebClient` ile)

```yaml
# application.yml — notification bölümü (Bölüm 9.1 ile tutarlı)
notification:
  provider: sendgrid                                 # DÜZELTME: Flat yapı (@ConditionalOnProperty ile uyumlu)
  api-key: ${SENDGRID_API_KEY:}
  from-email: ${NOTIFICATION_FROM_EMAIL:noreply@aestheticclinic.com}  # DÜZELTME: Tutarlı default
  from-name: ${NOTIFICATION_FROM_NAME:Aesthetic Clinic}
  sms:
    provider: netgsm
    username: ${NETGSM_USERNAME:}                    # DÜZELTME: usercode → username (Bölüm 26 ile tutarlı)
    password: ${NETGSM_PASSWORD:}
    sender-id: ${NETGSM_SENDER_ID:APPMESAJ}          # DÜZELTME: msgheader → sender-id (Bölüm 26 ile tutarlı)
```

> **NOT:** `RestTemplate` bean tanımı gerekli (Spring Boot otomatik oluşturmaz):
> ```kotlin
> // DÜZELTME AA-O8: Bu WebConfig, §7.5'teki ModuleGuardInterceptor WebConfig ile birleştirilmeli.
> // Tek bir WebConfig class'ı hem RestTemplate bean'ini hem de interceptor kaydını içermelidir.
> @Configuration
> class WebConfig(
>     private val moduleGuardInterceptor: ModuleGuardInterceptor
> ) : WebMvcConfigurer {
>     @Bean
>     fun restTemplate(builder: RestTemplateBuilder): RestTemplate = builder.build()
>
>     override fun addInterceptors(registry: InterceptorRegistry) {
>         registry.addInterceptor(moduleGuardInterceptor)
>             .addPathPatterns("/api/admin/**", "/api/public/**", "/api/client/**", "/api/staff/**")
>     }
> }
> ```

### 18.4.1 EmailService + SmsService Interface'leri

```kotlin
/** E-posta gönderim soyutlaması */
interface EmailService {
    fun send(to: String, subject: String, htmlBody: String)
    fun healthCheck()    // DÜZELTME: Health indicator için (Bölüm 25.1)
}

/** SMS gönderim soyutlaması */
interface SmsService {
    fun send(to: String, body: String)
}

/** SendGrid implementasyonu */
@Service
@ConditionalOnProperty("notification.provider", havingValue = "sendgrid")
class SendGridEmailService(
    @Value("\${notification.api-key}") private val apiKey: String,
    @Value("\${notification.from-email}") private val fromEmail: String,
    @Value("\${notification.from-name:Aesthetic Clinic}") private val fromName: String
) : EmailService {

    /** Sağlık kontrolü — Health indicator tarafından çağrılır */
    override fun healthCheck() {
        // SendGrid API'ye basit bir istek atarak erişilebilirliği doğrula
        val sg = SendGrid(apiKey)
        sg.api(Request().apply { method = Method.GET; endpoint = "scopes" })
    }
    // SendGrid API çağrısı
    override fun send(to: String, subject: String, htmlBody: String) {
        val sg = SendGrid(apiKey)
        val mail = Mail(Email(fromEmail, fromName), subject, Email(to), Content("text/html", htmlBody))
        val response = sg.api(Request().apply {
            method = Method.POST
            endpoint = "mail/send"
            body = mail.build()
        })
        if (response.statusCode !in 200..299) {
            throw RuntimeException("SendGrid hatası: ${response.statusCode} — ${response.body}")
        }
    }
}

/**
 * Netgsm SMS implementasyonu — Spring RestTemplate kullanır, ek dependency gerekmez.
 * Netgsm REST API dökümantasyonu: https://www.netgsm.com.tr/dokuman/
 */
@Service
@ConditionalOnProperty("notification.sms.provider", havingValue = "netgsm")
class NetgsmSmsService(
    @Value("\${notification.sms.username}") private val username: String,   // DÜZELTME: application.yml ile tutarlı
    @Value("\${notification.sms.password}") private val password: String,
    @Value("\${notification.sms.sender-id}") private val senderId: String,  // DÜZELTME: application.yml ile tutarlı
    private val restTemplate: RestTemplate
) : SmsService {
    private val logger = LoggerFactory.getLogger(NetgsmSmsService::class.java)
    private val apiUrl = "https://api.netgsm.com.tr/sms/send/get"

    override fun send(to: String, body: String) {
        // DÜZELTME S6: GET → POST (şifre URL parametresinde access log'lara düşer!)
        // apiUrl = "https://api.netgsm.com.tr/sms/send/xml" (POST endpoint)
        val xmlBody = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mainbody>
                <header>
                    <usercode>${username}</usercode>
                    <password>${password}</password>
                    <msgheader>${senderId}</msgheader>
                </header>
                <body>
                    <msg><![CDATA[${body}]]></msg>
                    <no>${to.replace("+", "")}</no>
                </body>
            </mainbody>
        """.trimIndent()

        val headers = org.springframework.http.HttpHeaders().apply {
            contentType = org.springframework.http.MediaType.APPLICATION_XML
        }
        val request = org.springframework.http.HttpEntity(xmlBody, headers)
        val response = restTemplate.postForObject(apiUrl, request, String::class.java)
        // Netgsm response: "00" = başarılı, "30" = hatalı numara, "70" = yetersiz bakiye
        if (response == null || !response.startsWith("00")) {
            logger.error("Netgsm SMS hatası — gsmno={}, response={}", to, response)
            throw RuntimeException("SMS gönderilemedi: $response")
        }
    }
}
```

---

## 19. Background Job Framework

### 19.1 Job Altyapısı

```kotlin
// AsyncConfig.kt (Bölüm 2.4'te tanımlandı) + @Scheduled kullanımı
// DÜZELTME P3/AA-T7: ShedLock — Multi-instance duplicate job koruması

// build.gradle.kts:
// implementation("net.javacrumbs.shedlock:shedlock-spring:5.16.0")
// implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0")

// ShedLockConfig.kt:
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
class ShedLockConfig(private val dataSource: DataSource) {
    @Bean
    fun lockProvider(): LockProvider = JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(JdbcTemplate(dataSource))
            .usingDbTime()
            .build()
    )
}

// Flyway migration — V25__create_shedlock_table.sql:
// CREATE TABLE shedlock (
//     name VARCHAR(64) NOT NULL PRIMARY KEY,
//     lock_until TIMESTAMP(3) NOT NULL,
//     locked_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
//     locked_by VARCHAR(255) NOT NULL
// ) ENGINE=InnoDB;

@Component
class ScheduledJobs(
    private val tenantAwareScheduler: TenantAwareScheduler,
    private val subscriptionService: SubscriptionService,
    private val appointmentRepository: AppointmentRepository,
    private val appointmentService: AppointmentService,              // NO_SHOW updateStatus çağrısı
    private val contactMessageRepository: ContactMessageRepository,  // DÜZELTME: Eksik dependency
    private val notificationService: NotificationService,
    private val siteSettingsRepository: SiteSettingsRepository       // DÜZELTME: Tenant timezone
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ScheduledJobs::class.java)
    }

    // Trial süresi dolan tenant'ları pasifleştir
    @Scheduled(cron = "0 0 2 * * ?")  // Her gece 02:00
    @SchedulerLock(name = "checkExpiredTrials", lockAtLeastFor = "4m", lockAtMostFor = "10m")
    fun checkExpiredTrials() {
        tenantAwareScheduler.executeForAllTenants { tenant ->
            subscriptionService.checkAndExpireTrial(tenant.id!!)
        }
    }

    // 30 günden eski okunmuş mesajları temizle
    @Scheduled(cron = "0 0 3 * * ?")  // Her gece 03:00
    @SchedulerLock(name = "cleanupOldMessages", lockAtLeastFor = "4m", lockAtMostFor = "10m")
    fun cleanupOldMessages() {
        tenantAwareScheduler.executeForAllTenants { tenant ->
            // DÜZELTME: Tenant timezone ile 30 gün öncesini hesapla
            val settings = siteSettingsRepository.findByTenantId(tenant.id!!)
            val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")
            val cutoff = ZonedDateTime.now(tenantZone).minusDays(30).toInstant()  // DÜZELTME: Instant (entity field tipi ile tutarlı)
            // Sadece okunmuş mesajları sil
            contactMessageRepository.deleteByIsReadTrueAndCreatedAtBefore(cutoff)
        }
    }

    // Randevu sonrası değerlendirme isteği gönder (24 saat sonra)
    @Scheduled(fixedRate = 3_600_000)  // Her saat
    fun sendReviewRequests() {
        tenantAwareScheduler.executeForAllTenants { tenant ->
            // DÜZELTME: Tenant timezone ile "24 saat önce" hesapla
            val settings = siteSettingsRepository.findByTenantId(tenant.id!!)
            val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")
            val yesterday = ZonedDateTime.now(tenantZone).minusHours(24).toInstant()  // DÜZELTME: Instant (entity field tipi ile tutarlı)

            val completedAppointments = appointmentRepository
                .findCompletedWithoutReview(yesterday)

            completedAppointments.forEach { appointment ->
                try {                                              // DÜZELTME: Per-appointment error handling
                    notificationService.sendReviewRequest(notificationService.toContext(appointment))
                } catch (e: Exception) {
                    logger.error("Değerlendirme isteği gönderilemedi — appointment={}", appointment.id, e)
                }
            }
        }
    }

    /**
     * Otomatik NO_SHOW işaretleme.
     * Randevu bitiş saati + 1 saat geçmiş ve status hala CONFIRMED → NO_SHOW yap.
     * NO_SHOW yapıldığında müşteri sayacı artırılır, 3 kez → kara liste.
     */
    @Scheduled(fixedRate = 3_600_000)  // Her saat
    fun markNoShowAppointments() {
        tenantAwareScheduler.executeForAllTenants { tenant ->
            val settings = siteSettingsRepository.findByTenantId(tenant.id!!)
            val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")
            val now = ZonedDateTime.now(tenantZone)
            // Randevu endTime + 1 saat geçmişse ve hala CONFIRMED → NO_SHOW
            val cutoffDate = now.minusHours(1).toLocalDate()
            val cutoffTime = now.minusHours(1).toLocalTime()

            val noShows = appointmentRepository.findConfirmedBeforeDateTime(
                tenant.id!!, cutoffDate, cutoffTime
            )
            noShows.forEach { appointment ->
                try {
                    appointmentService.updateStatus(appointment.id!!, AppointmentStatus.NO_SHOW)
                    logger.info("[tenant={}] Randevu otomatik NO_SHOW: {} (tarih: {}, client: {})",
                        tenant.id, appointment.id, appointment.date, appointment.client?.email)
                } catch (e: Exception) {
                    logger.error("[tenant={}] NO_SHOW işaretlenemedi: {}", tenant.id, appointment.id, e)
                }
            }
        }
    }
}
```

### 19.2 Retry Mekanizması

```kotlin
// Bildirim gönderimi başarısız olursa 3 kez dene
@Async("taskExecutor")
@Retryable(
    value = [NotificationDeliveryException::class],
    maxAttempts = 3,
    backoff = Backoff(delay = 5000, multiplier = 2.0)  // 5s, 10s, 20s
)
fun sendEmail(to: String, subject: String, body: String) {
    // SendGrid API çağrısı
}

@Recover
fun recoverSendEmail(e: NotificationDeliveryException, to: String, subject: String, body: String) {
    logger.error("E-posta gönderilemedi (3 deneme sonrası): {} — {}", to, e.message)
    // NotificationLog'a FAILED olarak kaydet
}

// Not: spring-retry + spring-boot-starter-aop zaten build.gradle.kts'te mevcut (Bölüm 9.2)
// @EnableRetry annotation'ı bir @Configuration sınıfında aktifleştirilmeli
```

---

## 20. KVKK / GDPR Uyumluluk

Türkiye'de KVKK (Kişisel Verilerin Korunması Kanunu), AB'de GDPR kapsamında:

### 20.1 Veri Dışa Aktarma (Hakkı: Taşınabilirlik)

```kotlin
// API: GET /api/auth/my-data → Kullanıcının tüm verisini JSON olarak indir
@GetMapping("/my-data")
@PreAuthorize("isAuthenticated()")
fun exportMyData(): ResponseEntity<ByteArray> {
    val userId = SecurityContextHolder.getContext().authentication.name
    val data = gdprService.exportUserData(userId)
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=my-data.json")
        .contentType(MediaType.APPLICATION_JSON)
        .body(data)
}
```

### 20.2 Veri Silme (Hakkı: Unutulma)

```kotlin
// API: DELETE /api/auth/my-account → Hesap ve ilişkili verileri sil
@DeleteMapping("/my-account")
@PreAuthorize("isAuthenticated()")
fun deleteMyAccount(): ApiResponse<Nothing> {
    val userId = SecurityContextHolder.getContext().authentication.name
    gdprService.deleteUserAndRelatedData(userId)
    return ApiResponse(message = "Hesabınız ve ilişkili verileriniz silindi")
}

```

### 20.2.1 GdprService — Veri Dışa Aktarma & Silme Servisi

```kotlin
/** DÜZELTME: GdprService tanımı eklendi — Bölüm 20.1 ve 20.2'de çağrılıyor */
@Service
class GdprService(
    private val userRepository: UserRepository,
    private val appointmentRepository: AppointmentRepository,
    private val contactMessageRepository: ContactMessageRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val reviewRepository: ReviewRepository,
    private val objectMapper: ObjectMapper
) {
    /**
     * Kullanıcının tüm verisini JSON byte array olarak dışa aktar
     * (KVKK/GDPR Veri Taşınabilirlik Hakkı)
     */
    fun exportUserData(userId: String): ByteArray {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("Kullanıcı bulunamadı: $userId") }

        val data = mapOf(
            "user" to mapOf(
                "name" to user.name,
                "email" to user.email,
                "phone" to user.phone,
                "role" to user.role.name,
                "createdAt" to user.createdAt.toString()
            ),
            "appointments" to appointmentRepository.findByClientEmail(user.email)
                .map { mapOf("date" to it.date.toString(), "service" to it.primaryService?.title, "status" to it.status.name) },
            "reviews" to reviewRepository.findByUserId(userId)
                .map { mapOf("rating" to it.rating, "comment" to it.comment, "createdAt" to it.createdAt.toString()) },
            "exportedAt" to Instant.now().toString()
        )

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data)
    }

    /**
     * Kullanıcı hesabını ve ilişkili verileri sil/anonimleştir
     * (KVKK/GDPR Unutulma Hakkı)
     */
    @Transactional
    fun deleteUserAndRelatedData(userId: String) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("Kullanıcı bulunamadı: $userId") }

        // 1. Randevulardaki kişisel bilgileri anonimleştir (yasal zorunluluk: kayıt tutma)
        appointmentRepository.anonymizeByClientEmail(user.email)   // clientName → "Anonim", clientEmail → "", clientPhone → ""

        // 2. Değerlendirmeleri anonimleştir
        reviewRepository.anonymizeByUserId(userId)                 // comment → "[Silindi]"

        // 3. İletişim mesajlarını sil
        contactMessageRepository.deleteByEmail(user.email)

        // 4. Refresh token'ları sil
        refreshTokenRepository.deleteByUserId(userId)

        // 5. Kullanıcı kaydını sil
        userRepository.delete(user)

        // NOT: Fatura bilgileri (invoices) yasal zorunluluk nedeniyle 10 yıl saklanır — silinmez
    }
}
```

### 20.3 Rıza Yönetimi

```kotlin
@Entity
@Table(name = "consent_records")
class ConsentRecord : TenantAwareEntity() {             // DÜZELTME: TenantAwareEntity extend
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null
    // DÜZELTME AA-T6: String userId → JPA ilişki (FK constraint ile veri bütünlüğü)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    var user: User? = null
    // tenantId → TenantAwareEntity'den miras alınır

    @Enumerated(EnumType.STRING)
    var consentType: ConsentType = ConsentType.TERMS_OF_SERVICE

    var isGranted: Boolean = false
    var grantedAt: Instant? = null                       // DÜZELTME: LocalDateTime → Instant (UTC)
    var revokedAt: Instant? = null                       // DÜZELTME: LocalDateTime → Instant
    var ipAddress: String? = null
}

enum class ConsentType {
    TERMS_OF_SERVICE,          // Kullanım koşulları
    PRIVACY_POLICY,            // Gizlilik politikası
    MARKETING_EMAIL,           // Pazarlama e-postaları
    MARKETING_SMS,             // Pazarlama SMS'leri
    DATA_PROCESSING            // Veri işleme onayı
}
```

---

## 21. API Versiyonlama

> **NOT (AA-G5):** Şu an tek versiyon (/api/) yeterlidir. Breaking change gerektiğinde
> aşağıdaki yapı uygulanacaktır. Erken versiyonlama gereksiz karmaşıklık ekler.

```
/api/v1/public/services          ← Mevcut versiyon
/api/v2/public/services          ← Gelecek breaking change

// Controller'da:
@RestController
@RequestMapping("/api/v1/public/services")
class ServiceControllerV1 { ... }

@RestController
@RequestMapping("/api/v2/public/services")
class ServiceControllerV2 { ... }

// Deprecation header:
// HTTP Response → Sunset: Sat, 01 Jan 2027 00:00:00 GMT
// HTTP Response → Deprecation: true
// HTTP Response → Link: </api/v2/public/services>; rel="successor-version"
```

---

## 22. Reporting & Analytics

### 22.1 Dashboard İstatistik Endpoint'leri

```
GET /api/admin/dashboard/stats
Response:
{
  "today": {
    "appointments": 12,
    "completedAppointments": 8,
    "revenue": 4500.00,
    "newClients": 3
  },
  "thisWeek": {
    "appointments": 68,
    "revenue": 24000.00,
    "cancellationRate": 0.08,
    "noShowRate": 0.03
  },
  "thisMonth": {
    "appointments": 240,
    "revenue": 86000.00,
    "topServices": [
      {"name": "Botoks", "count": 45, "revenue": 22500.00},
      {"name": "Dolgu", "count": 32, "revenue": 19200.00}
    ],
    "staffPerformance": [
      {"name": "Dr. Ayşe", "appointments": 80, "revenue": 32000.00, "avgRating": 4.8},
      {"name": "Dr. Mehmet", "appointments": 65, "revenue": 26000.00, "avgRating": 4.6}
    ]
  }
}

GET /api/admin/reports/revenue?from=2026-01-01&to=2026-02-01&groupBy=daily
GET /api/admin/reports/appointments?from=2026-01-01&to=2026-02-01&staffId=xxx
GET /api/admin/reports/clients?from=2026-01-01&to=2026-02-01
GET /api/admin/reports/export?format=xlsx&type=revenue&from=2026-01-01&to=2026-02-01
```

### 22.2 DashboardService Implementasyonu (DÜZELTME F10)

```kotlin
@Service
class DashboardService(
    private val appointmentRepository: AppointmentRepository,
    private val paymentRepository: PaymentRepository,
    private val userRepository: UserRepository,
    private val reviewRepository: ReviewRepository
) {
    /** Bugünkü istatistikler */
    fun getTodayStats(tenantId: String): TodayStats {
        val today = LocalDate.now()
        val todayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant()

        val appointments = appointmentRepository.countByTenantIdAndDate(tenantId, today)
        val completed = appointmentRepository.countByTenantIdAndDateAndStatus(
            tenantId, today, AppointmentStatus.COMPLETED
        )
        val revenue = paymentRepository.sumAmountByTenantIdAndPaidAtAfter(tenantId, todayStart) ?: BigDecimal.ZERO
        val newClients = userRepository.countByTenantIdAndRoleAndCreatedAtAfter(
            tenantId, Role.CLIENT, todayStart
        )

        return TodayStats(appointments, completed, revenue, newClients)
    }

    /** Haftalık/aylık istatistikler */
    fun getPeriodStats(tenantId: String, from: LocalDate, to: LocalDate): PeriodStats {
        val fromInstant = from.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val toInstant = to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

        val totalAppointments = appointmentRepository.countByTenantIdAndDateBetween(tenantId, from, to)
        val cancelled = appointmentRepository.countByTenantIdAndDateBetweenAndStatus(
            tenantId, from, to, AppointmentStatus.CANCELLED
        )
        val noShows = appointmentRepository.countByTenantIdAndDateBetweenAndStatus(
            tenantId, from, to, AppointmentStatus.NO_SHOW
        )
        val revenue = paymentRepository.sumAmountByTenantIdAndPaidAtBetween(tenantId, fromInstant, toInstant)
            ?: BigDecimal.ZERO

        return PeriodStats(
            appointments = totalAppointments,
            revenue = revenue,
            cancellationRate = if (totalAppointments > 0) cancelled.toDouble() / totalAppointments else 0.0,
            noShowRate = if (totalAppointments > 0) noShows.toDouble() / totalAppointments else 0.0
        )
    }

    /** En çok talep edilen hizmetler */
    fun getTopServices(tenantId: String, from: LocalDate, to: LocalDate, limit: Int = 10): List<ServiceStats> {
        return appointmentRepository.findTopServicesByTenantIdAndDateBetween(tenantId, from, to, PageRequest.of(0, limit))
    }

    /** Personel performans metrikleri */
    fun getStaffPerformance(tenantId: String, from: LocalDate, to: LocalDate): List<StaffPerformanceStats> {
        return appointmentRepository.findStaffPerformanceByTenantIdAndDateBetween(tenantId, from, to)
    }
}

// DTO'lar
data class TodayStats(val appointments: Long, val completed: Long, val revenue: BigDecimal, val newClients: Long)
data class PeriodStats(val appointments: Long, val revenue: BigDecimal, val cancellationRate: Double, val noShowRate: Double)
data class ServiceStats(val serviceName: String, val count: Long, val revenue: BigDecimal)
data class StaffPerformanceStats(val staffName: String, val appointments: Long, val revenue: BigDecimal, val avgRating: Double?)
```

---

## 23. CI/CD Pipeline

### 23.1 GitHub Actions

```yaml
# .github/workflows/ci.yml
name: CI Pipeline

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      mysql:
        image: mysql:8.0
        env:
          MYSQL_ROOT_PASSWORD: test
          MYSQL_DATABASE: aesthetic_test
        ports:
          - 3306:3306
        options: >-
          --health-cmd="mysqladmin ping"
          --health-interval=10s
          --health-timeout=5s
          --health-retries=5
        # NOT: character-set-server/collation Docker options'da geçersiz.
        # Karakter seti init SQL veya application.yml connection-init-sql ile ayarlanır.

      redis:                            # DÜZELTME: Redis service eklendi (rate limiting testleri için)
        image: redis:7-alpine
        ports:
          - 6379:6379
        options: >-
          --health-cmd="redis-cli ping"
          --health-interval=10s
          --health-timeout=5s
          --health-retries=5

    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Cache Gradle packages
        uses: actions/cache@v4
        with:
          path: |                       # DÜZELTME: Wrapper dir de cache'lenmeli
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle.kts') }}
          restore-keys: ${{ runner.os }}-gradle-

      - name: Build & Test
        run: ./gradlew build
        env:
          DB_HOST: localhost
          DB_PORT: 3306
          DB_NAME: aesthetic_test
          DB_USERNAME: root
          DB_PASSWORD: test
          REDIS_HOST: localhost
          REDIS_PORT: 6379
          JWT_SECRET: test-secret-key-for-ci-pipeline-min-256-bits-long-enough

  docker:
    needs: test
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Login to Registry          # DÜZELTME: Docker login eklendi
        run: echo "${{ secrets.REGISTRY_PASSWORD }}" | docker login registry.app.com -u "${{ secrets.REGISTRY_USERNAME }}" --password-stdin

      - name: Build Docker Image
        run: docker build -t aesthetic-backend:${{ github.sha }} .

      - name: Tag & Push                 # DÜZELTME: Hem SHA hem latest tag'i push
        run: |
          docker tag aesthetic-backend:${{ github.sha }} registry.app.com/aesthetic-backend:${{ github.sha }}
          docker tag aesthetic-backend:${{ github.sha }} registry.app.com/aesthetic-backend:latest
          docker push registry.app.com/aesthetic-backend:${{ github.sha }}
          docker push registry.app.com/aesthetic-backend:latest
```

---

## 24. Deployment & Altyapı

### 24.1 Prodüksiyon Docker Compose

```yaml
# Docker Compose V2 — 'version' alanı deprecated (Bölüm 10 ile tutarlı)
services:
  app:
    image: registry.app.com/aesthetic-backend:latest
    expose:
      - "8080"                         # DÜZELTME: Nginx arkasında — dışarıya port açma
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - JAVA_OPTS=-Xmx768m -Xms512m   # DÜZELTME: 1G limit → 768m max heap
      - DB_HOST=mysql
      - DB_PORT=3306
      - DB_NAME=aesthetic_saas
      - DB_USERNAME=${DB_USERNAME}
      - DB_PASSWORD=${DB_PASSWORD}
      - JWT_SECRET=${JWT_SECRET}
      - REDIS_HOST=redis               # DÜZELTME: Eksik Redis bağlantı bilgileri
      - REDIS_PORT=6379
      - REDIS_PASSWORD=${REDIS_PASSWORD}
      - FILE_PROVIDER=s3
      - S3_BUCKET=${S3_BUCKET}
      - S3_REGION=${S3_REGION}
      - S3_ACCESS_KEY=${S3_ACCESS_KEY}
      - S3_SECRET_KEY=${S3_SECRET_KEY}
      - SENDGRID_API_KEY=${SENDGRID_API_KEY}
      - NETGSM_USERNAME=${NETGSM_USERNAME}    # DÜZELTME T1: USERCODE → USERNAME (application.yml ile tutarlı)
      - NETGSM_PASSWORD=${NETGSM_PASSWORD}
      - IYZICO_API_KEY=${IYZICO_API_KEY}
      - IYZICO_SECRET_KEY=${IYZICO_SECRET_KEY}
      - IYZICO_BASE_URL=https://api.iyzipay.com  # DÜZELTME: Prod URL (sandbox değil)
      - SENTRY_DSN=${SENTRY_DSN}
      - FRONTEND_URL=${FRONTEND_URL}
    healthcheck:                        # DÜZELTME: App healthcheck eklendi
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
    restart: unless-stopped
    deploy:
      resources:
        limits:
          memory: 1G

  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf
      - ./certbot/conf:/etc/letsencrypt
      - ./certbot/www:/var/www/certbot
    depends_on:
      - app
    restart: unless-stopped

  mysql:
    image: mysql:8.0
    environment:
      - MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}
      - MYSQL_DATABASE=aesthetic_saas
      - MYSQL_USER=${DB_USERNAME}            # DÜZELTME: Root yerine ayrı uygulama kullanıcısı
      - MYSQL_PASSWORD=${DB_PASSWORD}
    volumes:
      - mysql-data:/var/lib/mysql
      - ./mysql/my.cnf:/etc/mysql/conf.d/custom.cnf
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD}
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD}", "ping"]  # DÜZELTME: Şifreli Redis
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  certbot:
    image: certbot/certbot
    volumes:
      - ./certbot/conf:/etc/letsencrypt
      - ./certbot/www:/var/www/certbot
    entrypoint: "/bin/sh -c 'trap exit TERM; while :; do certbot renew; sleep 12h & wait $${!}; done;'"
    restart: unless-stopped            # DÜZELTME: Restart policy eklendi

volumes:
  mysql-data:
  redis-data:
```

### 24.2 Nginx Konfigürasyonu (Wildcard Subdomain + TLS)

```nginx
# ─── Rate Limiting Zone (Nginx seviyesinde) ───
limit_req_zone $binary_remote_addr zone=api_limit:10m rate=30r/s;
limit_req_zone $binary_remote_addr zone=auth_limit:10m rate=5r/m;

# ─── HTTP → HTTPS Yönlendirme ───
server {
    listen 80;
    server_name *.app.com;

    # Let's Encrypt ACME challenge (certbot)
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

# ─── HTTPS Server ───
server {
    listen 443 ssl;              # DÜZELTME O2: Nginx 1.25+ "http2" directive deprecated
    http2 on;                    # Ayrı direktif olarak kullanılmalı
    server_name *.app.com;

    # ─── SSL/TLS ───
    ssl_certificate /etc/letsencrypt/live/app.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/app.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;       # DÜZELTME: Eski TLS protokollerini devre dışı bırak
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384;
    ssl_prefer_server_ciphers off;

    # ─── Güvenlik Header'ları ───
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header X-XSS-Protection "1; mode=block" always;

    # ─── Body Limiti (dosya yükleme) ───
    client_max_body_size 10m;

    # ─── Let's Encrypt (HTTPS içinde de) ───
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    # ─── Auth endpoint'leri — sıkı rate limit ───
    location /api/auth/ {
        limit_req zone=auth_limit burst=3 nodelay;
        proxy_pass http://app:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $remote_addr;     # DÜZELTME: spoofing önlemi — sadece gerçek IP
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 30s;
        proxy_connect_timeout 10s;
    }

    # ─── Genel API ───
    location / {
        limit_req zone=api_limit burst=50 nodelay;
        proxy_pass http://app:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $remote_addr;     # DÜZELTME: spoofing önlemi
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
        proxy_connect_timeout 10s;
        proxy_send_timeout 60s;
    }
}
```

### 24.3 MySQL Prodüksiyon Ayarları

```ini
# mysql/my.cnf
[mysqld]
innodb_buffer_pool_size = 1G
innodb_log_file_size = 256M
innodb_flush_log_at_trx_commit = 1
innodb_lock_wait_timeout = 10          # Pessimistic lock timeout (saniye)
max_connections = 200
character-set-server = utf8mb4
collation-server = utf8mb4_turkish_ci   # DÜZELTME: Bölüm 8 ile tutarlı (Türkçe İ/ı/Ş/ş sıralaması)
```

### 24.4 Veritabanı Yedekleme

```bash
# Otomatik günlük yedekleme (cron: her gece 04:00)
# 0 4 * * * /opt/scripts/backup.sh

#!/bin/bash
set -euo pipefail                   # DÜZELTME: Hata kontrolü aktif

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR=/opt/backups/mysql
CONTAINER_NAME=aesthetic-mysql      # Docker container adı

# Dizin kontrolü
mkdir -p "${BACKUP_DIR}"

# DÜZELTME: Docker container içinden mysqldump + --defaults-extra-file (şifre CLI'da görünmez)
docker exec "${CONTAINER_NAME}" \
    mysqldump --defaults-extra-file=/etc/mysql/conf.d/backup-credentials.cnf \
    --single-transaction \
    --routines \
    --triggers \
    aesthetic_saas | gzip > "${BACKUP_DIR}/aesthetic_saas_${TIMESTAMP}.sql.gz"

# Yedek dosya kontrolü
if [ ! -f "${BACKUP_DIR}/aesthetic_saas_${TIMESTAMP}.sql.gz" ]; then
    echo "HATA: Yedek dosyası oluşturulamadı!" >&2
    exit 1
fi

# 30 günden eski yerel yedekleri sil
find "${BACKUP_DIR}" -name "*.sql.gz" -mtime +30 -delete

# S3'e yükle (off-site backup)
aws s3 cp "${BACKUP_DIR}/aesthetic_saas_${TIMESTAMP}.sql.gz" \
    "s3://${S3_BUCKET}/backups/mysql/"

# NOT: S3 lifecycle policy ile 90 günden eski yedekler Glacier'a taşınmalı

echo "Backup completed: aesthetic_saas_${TIMESTAMP}.sql.gz"
```

> **NOT:** `backup-credentials.cnf` dosyası MySQL container'ına mount edilmeli:
> ```ini
> [mysqldump]
> user=root
> password=GIZLI_SIFRE
> ```

### 24.5 Application Prodüksiyon Konfigürasyonu

```yaml
# application-prod.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50          # Prodüksiyon: daha büyük pool
      minimum-idle: 10
      idle-timeout: 600000
      max-lifetime: 1800000
      connection-timeout: 30000

  jpa:
    properties:
      hibernate:
        format_sql: false            # Prodüksiyonda log azalt
        generate_statistics: false

  lifecycle:
    timeout-per-shutdown-phase: 30s  # Graceful shutdown max bekleme

  # Prodüksiyonda Swagger UI kapalı
  # (SecurityConfig'te permitAll olsa bile erişilemez)
springdoc:
  swagger-ui:
    enabled: false
  api-docs:
    enabled: false

server:
  port: 8080
  shutdown: graceful                 # Graceful shutdown — in-flight request'ler tamamlanır
  tomcat:
    connection-timeout: 5000
    max-connections: 8192
    threads:
      max: 200
      min-spare: 20

sentry:
  traces-sample-rate: 0.1           # Prodüksiyonda %10 sampling (dev'deki 1.0'ı override)

logging:
  level:
    root: WARN
    com.aesthetic.backend: INFO
    org.hibernate.SQL: WARN
```

---

## 25. Monitoring & Observability (Genişletilmiş)

### 25.1 Custom Health Indicators

```kotlin
@Component
class TenantResolutionHealthIndicator(
    private val tenantRepository: TenantRepository
) : HealthIndicator {
    override fun health(): Health {
        return try {
            val count = tenantRepository.countByIsActiveTrue()
            Health.up()
                .withDetail("activeTenants", count)
                .build()
        } catch (e: Exception) {
            Health.down(e).build()
        }
    }
}

@Component
class NotificationServiceHealthIndicator(
    private val emailService: EmailService       // DÜZELTME: SendGridClient → EmailService interface
) : HealthIndicator {
    override fun health(): Health {
        return try {
            // SendGrid API'ye basit bir GET isteği yaparak erişilebilirliği kontrol et
            emailService.healthCheck()
            Health.up().build()
        } catch (e: Exception) {
            Health.down()
                .withDetail("error", "E-posta servisi erişilemez: ${e.message}")
                .build()
        }
    }
}
// NOT: EmailService interface'ine healthCheck() metodu eklenmelidir (Bölüm 18'de tanımlı)
```

### 25.2 Request Correlation ID

```kotlin
// CorrelationIdFilter.kt — Her isteğe benzersiz ID ata (distributed tracing)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val correlationId = request.getHeader("X-Correlation-ID")
            ?: UUID.randomUUID().toString()

        MDC.put("correlationId", correlationId)
        MDC.put("tenantId", TenantContext.getTenantIdOrNull() ?: "system")
        response.setHeader("X-Correlation-ID", correlationId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            // DÜZELTME: MDC.clear() tüm key'leri siler (TenantFilter'ınkileri de!)
            // Sadece bu filter'ın eklediği key'leri temizle
            MDC.remove("correlationId")
            // tenantId → TenantFilter tarafından yönetilir, burada silme
        }
    }
}

// Logback pattern'ında correlationId + tenantId otomatik görünür:
// %d{ISO8601} [%X{correlationId}] [tenant=%X{tenantId}] %-5level %logger - %msg%n
```

### 25.3 Error Tracking (Sentry)

```kotlin
// Not: Sentry dependency zaten build.gradle.kts'te mevcut (Bölüm 9.2)
// implementation("io.sentry:sentry-spring-boot-starter-jakarta:7.14.0")

// application.yml:
// sentry:
//   dsn: ${SENTRY_DSN:}
//   traces-sample-rate: 0.1
//   environment: ${SPRING_PROFILES_ACTIVE:dev}
```

---

## 26. Ortam Değişkenleri (Güncellenmiş)

```env
# ─── Veritabanı ───
DB_HOST=localhost
DB_PORT=3306
DB_NAME=aesthetic_saas
DB_USERNAME=root
DB_PASSWORD=secret

# ─── JWT ───
JWT_SECRET=min-256-bit-secret-key-for-production-use

# ─── Server ───
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=dev           # dev | staging | prod
JAVA_OPTS=-Xmx512m -Xms256m         # JVM memory (Bölüm 10 docker-compose'da kullanılır)

# ─── Redis ───
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=redis-secret

# ─── Dosya Yükleme ───
FILE_PROVIDER=local                  # local | s3 | minio
S3_BUCKET=aesthetic-saas
S3_REGION=eu-central-1
S3_ACCESS_KEY=
S3_SECRET_KEY=

# ─── E-posta (SendGrid) ───
SENDGRID_API_KEY=
NOTIFICATION_FROM_EMAIL=noreply@aestheticclinic.com  # DÜZELTME: Tutarlı default (Bölüm 9.1 ile)
NOTIFICATION_FROM_NAME=Aesthetic Clinic               # DÜZELTME: Eksik env var eklendi

# ─── SMS (Netgsm — Türkiye) ───
NETGSM_USERNAME=                      # DÜZELTME: NETGSM_USERCODE → NETGSM_USERNAME (Bölüm 18 ile tutarlı)
NETGSM_PASSWORD=
NETGSM_SENDER_ID=APPMESAJ            # DÜZELTME: NETGSM_HEADER → NETGSM_SENDER_ID (Bölüm 18 ile tutarlı)

# ─── Ödeme (iyzico — Türkiye) ───
IYZICO_API_KEY=
IYZICO_SECRET_KEY=
IYZICO_BASE_URL=https://sandbox-api.iyzipay.com    # Prod: https://api.iyzipay.com

# ─── Error Tracking ───
SENTRY_DSN=

# ─── Frontend URL (CORS) ───
FRONTEND_URL=http://localhost:3000
```

---

---

## 28. Fonksiyonel Eklemeler (Analiz Sonrası)

> Bu bölüm, backend_analysis.md kapsamlı analizi sonucunda tespit edilen fonksiyonel eksiklikleri ve mimari iyileştirmeleri içerir.

### 28.1 Staff-Service İlişkisi (F1)

Personelin hangi hizmetleri sunduğunu belirleyen many-to-many ilişki. Randevu oluşturma sırasında yalnızca ilgili hizmeti sunan personeller listelenir.

```kotlin
// AvailabilityService — F1 güncellemesi: findAvailableStaff sorgusuna JOIN eklendi
@Query("""
    SELECT u FROM User u
    JOIN u.services s
    WHERE u.tenantId = :tenantId
    AND u.role = 'STAFF'
    AND u.isActive = true
    AND s.id = :serviceId
    AND NOT EXISTS (
        SELECT a FROM Appointment a
        WHERE a.staff.id = u.id
        AND a.date = :date
        AND a.status NOT IN ('CANCELLED', 'NO_SHOW')
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    )
""")
fun findAvailableStaffForService(
    @Param("tenantId") tenantId: String,
    @Param("serviceId") serviceId: String,
    @Param("date") date: LocalDate,
    @Param("startTime") startTime: LocalTime,
    @Param("endTime") endTime: LocalTime
): List<User>

// Public API — müşterilerin hizmete göre personel listesi alması
@RestController
@RequestMapping("/api/public")
class PublicStaffController(private val availabilityService: AvailabilityService) {
    @GetMapping("/staff")
    fun getStaffByService(
        @RequestParam serviceId: String,
        @RequestParam(required = false) date: LocalDate?
    ): ResponseEntity<List<StaffPublicResponse>> {
        // DÜZELTME AA-E11: StaffPublicResponse staff_services ilişkisiyle desteklenir
        val staff = if (date != null) {
            availabilityService.findAvailableStaffForDate(serviceId, date)
        } else {
            availabilityService.findStaffForService(serviceId)
        }
        return ResponseEntity.ok(staff.map { StaffPublicResponse.from(it) })
    }
}

data class StaffPublicResponse(
    val id: String,
    val name: String,
    val title: String?,      // "Dr.", "Uzm." vb.
    val image: String?,
    val services: List<String>   // Sunduğu hizmet adları
) {
    companion object {
        fun from(user: User) = StaffPublicResponse(
            id = user.id!!,
            name = user.name,
            title = user.title,
            image = user.image,
            services = user.services.map { it.title }
        )
    }
}
```

### 28.2 Randevu Yeniden Planlama — Reschedule (F2)

```kotlin
// AppointmentService — reschedule metodu
@Transactional(isolation = Isolation.READ_COMMITTED)
fun rescheduleAppointment(
    id: String,
    newDate: LocalDate,
    newTime: LocalTime,
    newStaffId: String? = null
): Appointment {
    val tenantId = TenantContext.getTenantId()
    val appointment = appointmentRepository.findById(id)
        .orElseThrow { ResourceNotFoundException("Randevu bulunamadı: $id") }

    // Sadece PENDING veya CONFIRMED durumundaki randevular yeniden planlanabilir
    require(appointment.status in listOf(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED)) {
        "Sadece bekleyen veya onaylanmış randevular yeniden planlanabilir"
    }

    // İptal politikası kontrolü (reschedule için de geçerli)
    validateCancellationPolicy(appointment)

    val staffId = newStaffId ?: appointment.staff?.id
        ?: throw IllegalStateException("Personel belirtilmemiş")
    val duration = appointment.endTime.toSecondOfDay() - appointment.startTime.toSecondOfDay()
    val newEndTime = newTime.plusSeconds(duration.toLong())

    // Çakışma kontrolü
    val conflicts = appointmentRepository.findConflictingAppointmentsWithLock(
        tenantId, staffId, newDate, newTime, newEndTime
    ).filter { it.id != id }  // Kendi kendisiyle çakışma kontrolü

    if (conflicts.isNotEmpty()) {
        throw AppointmentConflictException("Seçilen zaman diliminde başka bir randevu mevcut")
    }

    // Eski değerleri logla
    val oldDate = appointment.date
    val oldTime = appointment.startTime
    val oldStaffId = appointment.staff?.id

    // Güncelle
    appointment.date = newDate
    appointment.startTime = newTime
    appointment.endTime = newEndTime
    if (newStaffId != null) {
        appointment.staff = userRepository.findById(newStaffId)
            .orElseThrow { ResourceNotFoundException("Personel bulunamadı: $newStaffId") }
    }

    val saved = appointmentRepository.save(appointment)

    // Bildirim gönder (APPOINTMENT_RESCHEDULED — NotificationType'ta zaten tanımlı)
    val ctx = notificationService.toContext(saved)
    notificationService.sendAppointmentRescheduled(ctx, oldDate.toString(), oldTime.toString())

    // Audit log
    auditLogService.log(
        action = "RESCHEDULE_APPOINTMENT",
        entityType = "Appointment",
        entityId = id,
        details = mapOf(
            "oldDate" to oldDate.toString(),
            "oldTime" to oldTime.toString(),
            "oldStaffId" to (oldStaffId ?: ""),
            "newDate" to newDate.toString(),
            "newTime" to newTime.toString(),
            "newStaffId" to (staffId)
        )
    )

    return saved
}
```

```kotlin
// NotificationService — reschedule bildirimi
@Async("taskExecutor")
@Retryable(value = [NotificationDeliveryException::class], maxAttempts = 3, backoff = Backoff(delay = 2000))
fun sendAppointmentRescheduled(ctx: NotificationContext, oldDate: String, oldTime: String) {
    val template = notificationTemplateRepository
        .findByTenantIdAndType(ctx.tenantId, NotificationType.APPOINTMENT_RESCHEDULED)
        ?: return

    val variables = mapOf(
        "clientName" to ctx.clientName,
        "serviceName" to ctx.serviceName,
        "oldDate" to oldDate,
        "oldTime" to oldTime,
        "newDate" to ctx.date,
        "newTime" to ctx.startTime,
        "staffName" to ctx.staffName
    )

    sendNotification(ctx, template, variables)
}
```

### 28.3 Client API Endpoint'leri (F3)

```kotlin
@RestController
@RequestMapping("/api/client")
class ClientController(
    private val appointmentService: AppointmentService,
    private val reviewService: ReviewService,
    private val userService: UserService
) {
    /** Kendi randevularım (geçmiş + gelecek) */
    @GetMapping("/my-appointments")
    fun getMyAppointments(
        @AuthenticationPrincipal principal: UserPrincipal,
        @RequestParam(required = false) status: AppointmentStatus?,
        pageable: Pageable
    ): ResponseEntity<Page<AppointmentResponse>> {
        val appointments = appointmentService.findByClientId(principal.userId, status, pageable)
        return ResponseEntity.ok(appointments)
    }

    /** Kendi değerlendirmelerim */
    @GetMapping("/my-reviews")
    fun getMyReviews(@AuthenticationPrincipal principal: UserPrincipal): ResponseEntity<List<ReviewResponse>> {
        return ResponseEntity.ok(reviewService.findByClientId(principal.userId))
    }

    /** Yeni değerlendirme ekle */
    @PostMapping("/reviews")
    fun createReview(
        @AuthenticationPrincipal principal: UserPrincipal,
        @Valid @RequestBody request: CreateReviewRequest
    ): ResponseEntity<ReviewResponse> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(reviewService.createReview(principal.userId, request))
    }

    /** Profil güncelleme (ad, telefon, e-posta) */
    @PutMapping("/profile")
    fun updateProfile(
        @AuthenticationPrincipal principal: UserPrincipal,
        @Valid @RequestBody request: UpdateProfileRequest
    ): ResponseEntity<UserResponse> {
        return ResponseEntity.ok(userService.updateClientProfile(principal.userId, request))
    }

    /** Randevu iptal (iptal politikası geçerli) */
    @PostMapping("/appointments/{id}/cancel")
    fun cancelAppointment(
        @AuthenticationPrincipal principal: UserPrincipal,
        @PathVariable id: String
    ): ResponseEntity<AppointmentResponse> {
        return ResponseEntity.ok(appointmentService.cancelByClient(principal.userId, id))
    }
}

data class UpdateProfileRequest(
    @field:NotBlank val name: String,
    @field:Pattern(regexp = "^\\+?[0-9]{10,15}$") val phone: String?,
    @field:Email val email: String
)
```

### 28.4 Misafir → Kayıtlı Müşteri Eşleştirme (F4)

```kotlin
// AuthService.register() — kayıt sırasında mevcut misafir randevuları eşleştir
@Transactional
fun register(request: RegisterRequest): AuthResponse {
    // ... mevcut kayıt mantığı ...
    val user = userRepository.save(newUser)

    // DÜZELTME F4: Misafir randevuları eşleştir
    // Kayıt öncesi email ile yapılmış ama clientId olmayan randevuları bul
    val guestAppointments = appointmentRepository
        .findByClientEmailAndClientIdIsNull(user.email)
    if (guestAppointments.isNotEmpty()) {
        guestAppointments.forEach { appointment ->
            appointment.clientId = user.id
        }
        appointmentRepository.saveAll(guestAppointments)
        logger.info("Misafir randevuları eşleştirildi — userId={}, count={}", user.id, guestAppointments.size)
    }

    // ... JWT oluşturma ve dönüş ...
    return generateAuthResponse(user)
}
```

### 28.5 Ödeme Yaşam Döngüsü (F5)

```kotlin
@Service
class PaymentLifecycleService(
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentRepository: PaymentRepository,
    private val iyzicoService: IyzicoService
) {
    /** Plan yükseltme — proration hesabı ile */
    @Transactional
    fun upgradePlan(tenantId: String, newPlan: PlanType): Subscription {
        val subscription = subscriptionRepository.findByTenantIdAndStatus(tenantId, SubscriptionStatus.ACTIVE)
            ?: throw ResourceNotFoundException("Aktif abonelik bulunamadı")

        val remainingDays = ChronoUnit.DAYS.between(LocalDate.now(), subscription.endDate)
        val dailyRateOld = subscription.monthlyPrice / BigDecimal(30)
        val dailyRateNew = getPlanPrice(newPlan) / BigDecimal(30)
        val prorationAmount = (dailyRateNew - dailyRateOld) * BigDecimal(remainingDays)

        if (prorationAmount > BigDecimal.ZERO) {
            iyzicoService.chargeProration(tenantId, prorationAmount)
        }

        subscription.plan = newPlan.name
        subscription.monthlyPrice = getPlanPrice(newPlan)
        subscription.maxStaff = getPlanLimits(newPlan).maxStaff
        subscription.maxAppointmentsPerMonth = getPlanLimits(newPlan).maxAppointments
        subscription.maxStorageMb = getPlanLimits(newPlan).maxStorageMb

        return subscriptionRepository.save(subscription)
    }

    /** Plan düşürme — dönem sonunda geçerli */
    @Transactional
    fun downgradePlan(tenantId: String, newPlan: PlanType): Subscription {
        val subscription = subscriptionRepository.findByTenantIdAndStatus(tenantId, SubscriptionStatus.ACTIVE)
            ?: throw ResourceNotFoundException("Aktif abonelik bulunamadı")

        // Hemen değiştirme — dönem sonunda geçerli olacak şekilde işaretle
        subscription.pendingPlanChange = newPlan.name
        return subscriptionRepository.save(subscription)
    }

    /** Otomatik yenileme job'ı — her gün 00:00'da çalışır */
    @Scheduled(cron = "0 0 0 * * ?")
    fun processAutoRenewals() {
        val expiringToday = subscriptionRepository.findByEndDateAndAutoRenewTrueAndStatus(
            LocalDate.now(), SubscriptionStatus.ACTIVE
        )
        expiringToday.forEach { subscription ->
            try {
                val payment = iyzicoService.chargeSubscription(subscription)
                subscription.endDate = subscription.endDate.plusMonths(
                    if (subscription.billingPeriod == "YEARLY") 12 else 1
                )
                // Bekleyen plan değişikliği varsa uygula
                subscription.pendingPlanChange?.let { newPlan ->
                    subscription.plan = newPlan
                    subscription.pendingPlanChange = null
                }
                subscriptionRepository.save(subscription)
            } catch (e: PaymentException) {
                // Grace period başlat: 7 gün, 3 retry
                subscription.status = SubscriptionStatus.PAST_DUE
                subscription.retryCount = 0
                subscription.nextRetryAt = Instant.now().plus(Duration.ofDays(2))
                subscriptionRepository.save(subscription)
            }
        }
    }

    /** Başarısız ödeme retry — her 6 saatte çalışır */
    @Scheduled(fixedRate = 21_600_000)
    fun retryFailedPayments() {
        val pastDue = subscriptionRepository.findByStatusAndNextRetryAtBefore(
            SubscriptionStatus.PAST_DUE, Instant.now()
        )
        pastDue.forEach { subscription ->
            try {
                iyzicoService.chargeSubscription(subscription)
                subscription.status = SubscriptionStatus.ACTIVE
                subscription.retryCount = 0
                subscription.nextRetryAt = null
                subscriptionRepository.save(subscription)
            } catch (e: PaymentException) {
                subscription.retryCount++
                if (subscription.retryCount >= 3) {
                    // 3 deneme sonrası → EXPIRED
                    subscription.status = SubscriptionStatus.EXPIRED
                } else {
                    subscription.nextRetryAt = Instant.now().plus(Duration.ofDays(2))
                }
                subscriptionRepository.save(subscription)
            }
        }
    }

    /** iyzico refund */
    fun refundPayment(paymentId: String): Payment {
        val payment = paymentRepository.findById(paymentId)
            .orElseThrow { ResourceNotFoundException("Ödeme bulunamadı: $paymentId") }
        iyzicoService.refund(payment.providerPaymentId!!)
        payment.status = PaymentStatus.REFUNDED
        return paymentRepository.save(payment)
    }
}
```

### 28.6 Varsayılan Bildirim Template'leri (F7)

```kotlin
// TenantOnboardingService — onboarding adımına varsayılan template ekleme
private fun createDefaultNotificationTemplates(tenantId: String) {
    val defaults = listOf(
        NotificationTemplate().apply {
            this.tenantId = tenantId
            this.type = NotificationType.APPOINTMENT_CONFIRMATION
            this.emailSubject = "Randevunuz Onaylandı"
            this.emailBody = """
                Sayın {{clientName}},
                {{date}} tarihinde saat {{startTime}}'da {{serviceName}} randevunuz onaylanmıştır.
                Personel: {{staffName}}
                İyi günler dileriz.
            """.trimIndent()
            this.smsBody = "{{clientName}}, {{date}} {{startTime}} randevunuz onaylandı. {{serviceName}} - {{staffName}}"
            this.isEmailEnabled = true
            this.isSmsEnabled = true
        },
        NotificationTemplate().apply {
            this.tenantId = tenantId
            this.type = NotificationType.APPOINTMENT_REMINDER_24H
            this.emailSubject = "Randevu Hatırlatması — Yarın"
            this.emailBody = """
                Sayın {{clientName}},
                Yarın {{startTime}}'da {{serviceName}} randevunuz bulunmaktadır.
                Personel: {{staffName}}
                Randevunuzu iptal etmek veya değiştirmek için lütfen bizimle iletişime geçin.
            """.trimIndent()
            this.smsBody = "Hatırlatma: Yarın {{startTime}} {{serviceName}} randevunuz var. {{staffName}}"
            this.isEmailEnabled = true
            this.isSmsEnabled = true
        },
        NotificationTemplate().apply {
            this.tenantId = tenantId
            this.type = NotificationType.APPOINTMENT_REMINDER_1H
            this.emailSubject = "Randevu Hatırlatması — 1 Saat"
            this.emailBody = "Sayın {{clientName}}, 1 saat sonra {{serviceName}} randevunuz başlayacaktır."
            this.smsBody = "1 saat sonra {{serviceName}} randevunuz var. {{staffName}}"
            this.isEmailEnabled = false   // Varsayılan: sadece SMS
            this.isSmsEnabled = true
        },
        NotificationTemplate().apply {
            this.tenantId = tenantId
            this.type = NotificationType.APPOINTMENT_CANCELLED
            this.emailSubject = "Randevunuz İptal Edildi"
            this.emailBody = "Sayın {{clientName}}, {{date}} tarihli {{serviceName}} randevunuz iptal edilmiştir."
            this.smsBody = "{{clientName}}, {{date}} {{startTime}} randevunuz iptal edildi."
            this.isEmailEnabled = true
            this.isSmsEnabled = true
        },
        NotificationTemplate().apply {
            this.tenantId = tenantId
            this.type = NotificationType.APPOINTMENT_RESCHEDULED
            this.emailSubject = "Randevunuz Yeniden Planlandı"
            this.emailBody = """
                Sayın {{clientName}},
                Randevunuz {{oldDate}} {{oldTime}} → {{newDate}} {{newTime}} olarak değiştirilmiştir.
                Personel: {{staffName}}
            """.trimIndent()
            this.smsBody = "Randevunuz değişti: {{newDate}} {{newTime}}. {{serviceName}} - {{staffName}}"
            this.isEmailEnabled = true
            this.isSmsEnabled = true
        },
        NotificationTemplate().apply {
            this.tenantId = tenantId
            this.type = NotificationType.REVIEW_REQUEST
            this.emailSubject = "Deneyiminizi Değerlendirin"
            this.emailBody = """
                Sayın {{clientName}},
                {{serviceName}} hizmetimizden memnun kaldınız mı?
                Deneyiminizi değerlendirerek bize yardımcı olabilirsiniz.
            """.trimIndent()
            this.smsBody = "{{clientName}}, {{serviceName}} hizmetimizi değerlendirir misiniz?"
            this.isEmailEnabled = true
            this.isSmsEnabled = false
        },
        // ── Eksik template'ler (BUG-3/F7 düzeltmesi) ──
        NotificationTemplate().apply {
            this.tenantId = tenantId
            this.type = NotificationType.WELCOME
            this.emailSubject = "Hoş Geldiniz — {{siteName}}"
            this.emailBody = """
                Sayın {{clientName}},
                Kaydınız başarıyla oluşturuldu! Artık online randevu alabilirsiniz.
                {{siteName}} ailesine hoş geldiniz.
            """.trimIndent()
            this.smsBody = "{{clientName}}, {{siteName}}'e hoş geldiniz! Kaydınız başarılı."
            this.isEmailEnabled = true
            this.isSmsEnabled = true
        },
        NotificationTemplate().apply {
            this.tenantId = tenantId
            this.type = NotificationType.PASSWORD_RESET
            this.emailSubject = "Şifre Sıfırlama"
            this.emailBody = """
                Sayın {{clientName}},
                Şifre sıfırlama talebiniz alınmıştır.
                Aşağıdaki bağlantıyı kullanarak yeni şifrenizi belirleyebilirsiniz:
                {{resetLink}}
                Bu bağlantı 1 saat geçerlidir. Talepte bulunmadıysanız bu e-postayı görmezden gelin.
            """.trimIndent()
            this.smsBody = "Şifre sıfırlama: {{resetLink}} (1 saat geçerli)"
            this.isEmailEnabled = true
            this.isSmsEnabled = false
        },
        NotificationTemplate().apply {
            this.tenantId = tenantId
            this.type = NotificationType.APPOINTMENT_NO_SHOW
            this.emailSubject = "Randevuya Gelinmedi"
            this.emailBody = """
                {{clientName}}, {{date}} tarihinde saat {{time}} randevusuna gelmemiştir.
                Bu müşterinin toplam gelmeme sayısı: {{noShowCount}}.
                Hizmet: {{serviceName}}, Personel: {{staffName}}
            """.trimIndent()
            this.smsBody = "{{clientName}} {{date}} {{time}} randevuya gelmedi ({{noShowCount}}. kez)"
            this.isEmailEnabled = true
            this.isSmsEnabled = false
        },
        NotificationTemplate().apply {
            this.tenantId = tenantId
            this.type = NotificationType.CLIENT_BLACKLISTED_NOTICE
            this.emailSubject = "Müşteri Kara Listede"
            this.emailBody = """
                {{clientName}} müşterisi {{noShowCount}} kez randevuya gelmemiştir.
                Sistem tarafından otomatik olarak kara listeye alınmıştır.
                Bu müşteri artık yeni randevu oluşturamaz.
            """.trimIndent()
            this.smsBody = "{{clientName}} {{noShowCount}} kez gelmedi, kara listeye alındı."
            this.isEmailEnabled = true
            this.isSmsEnabled = false
        }
    )
    notificationTemplateRepository.saveAll(defaults)
}
```

### 28.7 Otomatik Randevu Onay (F8)

```kotlin
// SiteSettings entity — yeni alan
var autoConfirmAppointments: Boolean = false    // DÜZELTME F8: Otomatik onay

// AppointmentService.createAppointment() — otomatik onay kontrolü
@Transactional(isolation = Isolation.READ_COMMITTED)
fun createAppointment(request: CreateAppointmentRequest): Appointment {
    // ... mevcut validasyon ve kaydetme mantığı ...
    val appointment = appointmentRepository.save(newAppointment)

    // DÜZELTME F8: Otomatik onay kontrolü
    val settings = siteSettingsRepository.findByTenantId(TenantContext.getTenantId())
    if (settings?.autoConfirmAppointments == true) {
        appointment.status = AppointmentStatus.CONFIRMED
        appointmentRepository.save(appointment)
    }

    // Bildirim gönder
    val ctx = notificationService.toContext(appointment)
    notificationService.sendAppointmentConfirmation(ctx)

    return appointment
}
```

### 28.8 STAFF Rolü — Kısıtlı Login (F9)

```kotlin
// AuthService — STAFF login desteği
// DÜZELTME F9: STAFF login engeli kaldırıldı — kısıtlı (read-only) erişim
fun login(request: LoginRequest): AuthResponse {
    val user = userRepository.findByEmailAndTenantId(request.email, TenantContext.getTenantId())
        ?: throw UnauthorizedException("Geçersiz e-posta veya şifre")

    // STAFF artık login YAPABİLİR (kısıtlı erişim)
    if (!user.isActive) {
        throw UnauthorizedException("Hesap devre dışı")
    }

    // ... mevcut şifre doğrulama, brute force kontrolü ...
    return generateAuthResponse(user)
}

// StaffController — STAFF read-only endpoint'leri
@RestController
@RequestMapping("/api/staff")
class StaffController(
    private val appointmentRepository: AppointmentRepository,
    private val workingHoursRepository: WorkingHoursRepository
) {
    /** Kendi takvimim — bugünkü randevular */
    @GetMapping("/my-calendar")
    fun getMyCalendar(
        @AuthenticationPrincipal principal: UserPrincipal,
        @RequestParam(required = false) date: LocalDate?
    ): ResponseEntity<List<AppointmentResponse>> {
        val targetDate = date ?: LocalDate.now()
        val appointments = appointmentRepository.findByTenantIdAndStaffIdAndDateOrderByStartTime(
            TenantContext.getTenantId(), principal.userId, targetDate
        )
        return ResponseEntity.ok(appointments.map { AppointmentResponse.from(it) })
    }

    /** Kendi çalışma saatlerim */
    @GetMapping("/my-schedule")
    fun getMySchedule(
        @AuthenticationPrincipal principal: UserPrincipal
    ): ResponseEntity<List<WorkingHoursResponse>> {
        val hours = workingHoursRepository.findByTenantIdAndStaffId(
            TenantContext.getTenantId(), principal.userId
        )
        return ResponseEntity.ok(hours.map { WorkingHoursResponse.from(it) })
    }

    /** Belirli bir randevunun detayı (sadece kendi randevuları) */
    @GetMapping("/appointments/{id}")
    fun getAppointmentDetail(
        @AuthenticationPrincipal principal: UserPrincipal,
        @PathVariable id: String
    ): ResponseEntity<AppointmentResponse> {
        val appointment = appointmentRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Randevu bulunamadı: $id") }

        // Sadece kendi randevusu mu kontrol et
        require(appointment.staff?.id == principal.userId) {
            throw AccessDeniedException("Bu randevuya erişim yetkiniz yok")
        }

        return ResponseEntity.ok(AppointmentResponse.from(appointment))
    }
}
```

### 28.9 Recurring Appointment Self-Invocation Düzeltmesi (F11 / AA-O3)

```kotlin
// DÜZELTME F11/AA-O3: Spring AOP proxy self-invocation sorunu.
// Aynı class içinde @Transactional/@Async metod çağrısı proxy'yi bypass eder.
// Çözüm: Recurring logic ayrı bir bean'e taşındı.

@Service
class RecurringAppointmentService(
    private val appointmentService: AppointmentService,
    private val appointmentRepository: AppointmentRepository
) {
    /**
     * Tekrarlayan randevu oluşturma — her instance için AppointmentService.createAppointment() çağırır.
     * Böylece @Transactional annotation'ı doğru çalışır (proxy üzerinden).
     */
    @Transactional
    fun createRecurringAppointments(
        baseRequest: CreateAppointmentRequest,
        recurrenceRule: RecurrenceRule
    ): List<Appointment> {
        val appointments = mutableListOf<Appointment>()
        var currentDate = baseRequest.date

        repeat(recurrenceRule.occurrences) {
            val request = baseRequest.copy(date = currentDate)
            try {
                val appointment = appointmentService.createAppointment(request)
                appointments.add(appointment)
            } catch (e: AppointmentConflictException) {
                // Çakışan tarihleri atla, diğerlerine devam et
                logger.warn("Recurring randevu çakışması atlandı — date={}", currentDate)
            }
            currentDate = when (recurrenceRule.frequency) {
                RecurrenceFrequency.DAILY -> currentDate.plusDays(1)
                RecurrenceFrequency.WEEKLY -> currentDate.plusWeeks(1)
                RecurrenceFrequency.BIWEEKLY -> currentDate.plusWeeks(2)
                RecurrenceFrequency.MONTHLY -> currentDate.plusMonths(1)
            }
        }

        return appointments
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RecurringAppointmentService::class.java)
    }
}
```

### 28.10 Mimari Notlar ve Gelecek Geliştirmeler

#### AA-O4: WorkingHours Çoklu Aralık Desteği
> **Gelecek geliştirme:** Mevcut yapı günde tek çalışma aralığı destekler (09:00-18:00).
> Öğle arası veya bölünmüş vardiyalar (09:00-12:00, 14:00-18:00) için WorkingHours entity'sine
> `@ElementCollection` ile `List<TimeRange>` desteği eklenmelidir. Bu değişiklik
> `AvailabilityService.getAvailableSlots()` algoritmasını da etkiler.

#### Timezone Validasyonu
```kotlin
// SiteSettings — timezone validasyonu
@Column(length = 50)
var timezone: String = "Europe/Istanbul"
    set(value) {
        // DÜZELTME: Geçersiz timezone değerlerini engelle
        try {
            ZoneId.of(value)
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("Geçersiz timezone: $value")
        }
        field = value
    }
```

#### ElementCollection Sıralama
> **NOT:** `@ElementCollection` kullanılan koleksiyonlarda `@OrderColumn` eklenmeli.
> Aksi halde Hibernate her güncelleme'de delete + re-insert yapar (performans sorunu).
> Örnek: `WorkingHours.breaks`, `Appointment.services` gibi ilişkilerde dikkat edilmeli.

#### Webhook İdempotency
```kotlin
// WebhookController — idempotency kontrolü
@PostMapping("/api/webhooks/iyzico")
fun handleIyzicoWebhook(@RequestBody payload: String, @RequestHeader headers: Map<String, String>): ResponseEntity<Void> {
    val eventId = extractEventId(payload)

    // DÜZELTME: Aynı event birden fazla kez işlenmesin
    if (processedWebhookEventRepository.existsByEventId(eventId)) {
        return ResponseEntity.ok().build()  // Zaten işlendi — 200 dön
    }

    // ... webhook işleme mantığı ...

    // İşlenen event'i kaydet
    processedWebhookEventRepository.save(ProcessedWebhookEvent().apply {
        this.eventId = eventId
        this.provider = "IYZICO"
        this.eventType = extractEventType(payload)
    })

    return ResponseEntity.ok().build()
}
```

#### Tika Singleton
```kotlin
// FileValidationService — Apache Tika singleton
// DÜZELTME: Her dosya yüklemede yeni Tika instance oluşturmak gereksiz.
// Tika thread-safe'dir, singleton yeterlidir.
class FileValidationService {
    companion object {
        private val tika = Tika()   // Singleton — thread-safe
    }

    fun detectMimeType(inputStream: InputStream): String {
        return tika.detect(inputStream)
    }
}
```

#### S4: Şifre Politikası (PasswordPolicyValidator)
```kotlin
// DÜZELTME S4: BCrypt hash var ama pre-hash validation YOK — "123456" kabul edilir!
// RegisterRequest ve ResetPasswordRequest'e @Password custom annotation eklenmeli.

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [PasswordPolicyValidator::class])
annotation class Password(
    val message: String = "Şifre politikasına uygun değil",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class PasswordPolicyValidator : ConstraintValidator<Password, String> {
    companion object {
        private val COMMON_PASSWORDS = setOf("123456", "password", "12345678", "qwerty", "abc123",
            "monkey", "1234567", "letmein", "trustno1", "dragon", "sifre123", "admin123")
    }

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return false
        val violations = mutableListOf<String>()
        if (value.length < 8) violations.add("En az 8 karakter olmalı")
        if (!value.any { it.isUpperCase() }) violations.add("En az 1 büyük harf olmalı")
        if (!value.any { it.isLowerCase() }) violations.add("En az 1 küçük harf olmalı")
        if (!value.any { it.isDigit() }) violations.add("En az 1 rakam olmalı")
        if (value.lowercase() in COMMON_PASSWORDS) violations.add("Çok yaygın bir şifre")
        if (violations.isNotEmpty()) {
            context.disableDefaultConstraintViolation()
            context.buildConstraintViolationWithTemplate(violations.joinToString("; "))
                .addConstraintViolation()
            return false
        }
        return true
    }
}

// Kullanım:
// data class RegisterRequest(
//     @field:Email val email: String,
//     @field:Password val password: String,   // ← Şifre politikası uygulanır
//     @field:NotBlank val name: String
// )
```

#### S5: Email Doğrulama (EmailVerificationService)
```kotlin
// DÜZELTME S5: Sahte email ile hesap oluşturulabiliyor — email doğrulama gerekli.

@Entity
@Table(name = "email_verifications")
class EmailVerification : TenantAwareEntity() {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null
    val userId: String = ""
    val token: String = ""                    // SecureRandom 64 karakter hex
    val expiresAt: Instant = Instant.now().plus(Duration.ofHours(1))
    var verifiedAt: Instant? = null
}

// Flyway migration — V24__create_email_verifications_table.sql:
// CREATE TABLE email_verifications (
//     id VARCHAR(36) NOT NULL PRIMARY KEY,
//     tenant_id VARCHAR(36) NOT NULL,
//     user_id VARCHAR(36) NOT NULL,
//     token VARCHAR(64) NOT NULL UNIQUE,
//     expires_at TIMESTAMP(6) NOT NULL,
//     verified_at TIMESTAMP(6) NULL,
//     created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
//     FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
//     FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
//     INDEX idx_email_verify_token (token)
// );

// User entity'ye eklenmeli:
// var isEmailVerified: Boolean = false

@Service
class EmailVerificationService(
    private val emailVerificationRepository: EmailVerificationRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService
) {
    fun sendVerificationEmail(userId: String) {
        val token = generateSecureToken()
        val verification = EmailVerification().apply {
            this.userId = userId
            this.token = token
        }
        emailVerificationRepository.save(verification)
        // E-posta gönder: {{verifyLink}} = /api/auth/verify-email?token=$token
    }

    // POST /api/auth/verify-email?token=xxx
    fun verifyEmail(token: String): Boolean {
        val verification = emailVerificationRepository.findByToken(token)
            ?: throw ResourceNotFoundException("Geçersiz doğrulama token'ı")
        if (verification.expiresAt.isBefore(Instant.now()))
            throw IllegalStateException("Doğrulama linki süresi dolmuş")
        if (verification.verifiedAt != null)
            throw IllegalStateException("E-posta zaten doğrulanmış")
        verification.verifiedAt = Instant.now()
        emailVerificationRepository.save(verification)
        val user = userRepository.findById(verification.userId).orElseThrow()
        user.isEmailVerified = true
        userRepository.save(user)
        return true
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
```

#### P1: Cache Stratejisi — Multi-Instance Uyarısı
> **DİKKAT:** Caffeine local cache (§13.3) multi-instance deployment'ta senkronize olmaz.
> Instance A cache'i invalidate ederse, Instance B eski veriyi 5 dk daha sunar.
>
> **Kısa vadeli çözüm:** Cache TTL'leri kısa tutulur (5 dk — kabul edilebilir stale).
>
> **Uzun vadeli çözüm:** Entity cache için Redis (`spring-boot-starter-data-redis` + `@Cacheable`)
> geçişi yapılmalıdır. Redis zaten rate limiting + session için mevcut,
> entity cache'i de taşımak tutarlı olur.

#### P2: TenantAwareScheduler Paralel İşleme
```kotlin
// Mevcut: Seri işleme (tüm tenant'lar for-loop ile)
// Sorun: 100+ tenant olduğunda job'lar geç tamamlanır

// ÖNERİ: Paralel versiyonu (CompletableFuture ile)
fun executeForAllTenantsParallel(action: (Tenant) -> Unit) {
    val tenants = tenantRepository.findAllByIsActiveTrue()
    val executor = Executors.newFixedThreadPool(
        minOf(tenants.size, Runtime.getRuntime().availableProcessors() * 2)
    )
    val futures = tenants.map { tenant ->
        CompletableFuture.runAsync({
            try {
                TenantContext.setTenantId(tenant.id!!)
                action(tenant)
            } catch (e: Exception) {
                logger.error("[tenant={}] Parallel scheduled task hatası: {}", tenant.slug, e.message, e)
            } finally {
                TenantContext.clear()
            }
        }, executor)
    }
    CompletableFuture.allOf(*futures.toTypedArray()).join()
    executor.shutdown()
}
// NOT: ThreadLocal (TenantContext) her thread'de ayrı set edilir — thread-safe.
```

#### P4: Index Optimizasyonu
> **Mevcut:** `idx_appt_conflict` — 6 sütunlu composite index (tenant_id, staff_id, date, start_time, end_time, status)
>
> **Sorun:** `status NOT IN (...)` negative condition index'i etkisiz kılabilir.
>
> **Öneri:** `(tenant_id, staff_id, date)` 3 sütunlu index + status filtresi uygulamada yapılmalı.
> MySQL optimizer negative IN condition'da index range scan yerine full scan tercih edebilir.
>
> **Ek:** Tüm `@ElementCollection` tabloları için `(parent_id)` index zaten FK ile oluşur
> ancak multi-tenant sorgularda `(tenant_id, parent_id)` composite index düşünülmeli.

#### D2: Cascade DELETE Uyarısı + Soft Delete Stratejisi
> **KRİTİK:** Tüm FK'larda `ON DELETE CASCADE` var — tenant silindiğinde TÜM veriler kaybolur.
>
> **Yasal risk:** Faturalar Türk Ticaret Kanunu gereği 10 yıl saklanmalıdır.
>
> **Öneri:**
> 1. Tenant silme yerine `is_active = false` (soft deactivation)
> 2. Fatura/ödeme entityleri fiziksel silme yerine soft delete kullanmalı
> 3. `TenantAwareEntity`'ye `deleted_at: Instant? = null` eklenmeli:

```kotlin
// DÜZELTME D2/16-1.3: Soft Delete Stratejisi
@MappedSuperclass
abstract class TenantAwareEntity {
    // ... mevcut alanlar ...

    // Soft delete desteği — fiziksel silme yerine
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    fun softDelete() { deletedAt = Instant.now() }
    val isDeleted: Boolean get() = deletedAt != null
}

// JPA entegrasyonu:
// @SQLDelete(sql = "UPDATE {tableName} SET deleted_at = NOW() WHERE id = ?")
// @Where(clause = "deleted_at IS NULL")
// veya Hibernate @FilterDef ile global soft delete filter

// DİKKAT:
// - ON DELETE CASCADE olan FK'lar child entity'leri fiziksel siler!
//   Soft delete ile bu FK'lar ON DELETE RESTRICT'e çevrilmeli.
// - GDPR silme talebinde deleted_at yerine gerçek anonimizasyon gerekebilir.
```

#### D3 + D4: Log Retention Stratejisi
> **notification_logs:** ~300 kayıt/gün → yılda ~110K (çoklanabilir)
> **audit_logs:** Her CRUD → milyarlarca satır potansiyeli
>
> **Retention stratejisi:**
> 1. **6 aylık aktif veri:** `notification_logs` ve `audit_logs` tablolarında son 6 ay tutulur
> 2. **Arşivleme:** 6 aydan eski veriler `_archive` tablolarına taşınır (aylık cron job)
> 3. **Partition önerisi (MySQL 8.0+):**
>    ```sql
>    ALTER TABLE audit_logs PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) (
>        PARTITION p2025_01 VALUES LESS THAN (UNIX_TIMESTAMP('2025-02-01')),
>        PARTITION p2025_02 VALUES LESS THAN (UNIX_TIMESTAMP('2025-03-01')),
>        -- ... aylık partition'lar
>        PARTITION p_future VALUES LESS THAN MAXVALUE
>    );
>    ```
> 4. **Uzun vadeli:** Elasticsearch veya MongoDB'ye taşıma (loglama-specific DB)

#### O1: CI/CD Artifact Stratejisi
> **Mevcut durum:** CI'da `./gradlew build` → JAR oluşturur, sonra `docker build` → Dockerfile JAR'ı kopyalar.
>
> **Sorun:** Multi-stage Dockerfile kullanılıyorsa CI'daki JAR kullanılmaz (Docker içinde tekrar build olur).
> Simple Dockerfile kullanılıyorsa CI'dan Docker context'e JAR aktarılmalıdır.
>
> **Karar:** Multi-stage Dockerfile tercih edilmeli — CI'da ayrı artifact paylaşımı gerekmez.
> Docker build kendi içinde Gradle build yapar. CI'daki test aşaması sadece testler + lint için kalır.

#### O3: Certbot İlk Sertifika Oluşturma
```bash
# İlk deployment'ta sertifika oluşturma (renewal değil):
# 1. Nginx'i sadece HTTP ile başlat (SSL config'i yorum satırı):
docker compose up -d nginx

# 2. Certbot ile ilk sertifikayı al:
docker compose run --rm certbot certonly \
    --webroot -w /var/www/certbot \
    -d app.com -d "*.app.com" \
    --email admin@app.com \
    --agree-tos --no-eff-email

# 3. Nginx'i SSL config ile yeniden başlat:
docker compose restart nginx

# NOT: Wildcard sertifika (*.app.com) için DNS-01 challenge gerekir:
# docker compose run --rm certbot certonly \
#     --manual --preferred-challenges dns \
#     -d "*.app.com" -d app.com
```

#### O4: Docker Build — JAR Artifact
> Multi-stage Dockerfile kullanıldığında CI pipeline'da ayrı JAR aktarımı gerekmez.
> Dockerfile kendi içinde `./gradlew build` çalıştırır. Eğer simple Dockerfile tercih edilirse:
> ```yaml
> # GitHub Actions — artifact aktarımı:
> - uses: actions/upload-artifact@v4
>   with:
>     name: app-jar
>     path: build/libs/*.jar
> # docker job'unda:
> - uses: actions/download-artifact@v4
>   with:
>     name: app-jar
>     path: ./build/libs/
> ```

#### AA-D3: blog_posts Orphan Handling
> `ON DELETE SET NULL` zaten uygulanmış (satır 3665) — doğru tasarım.
> Admin panelinde "yazarı silinmiş" blog yazılarını filtreleme önerisi:
> ```kotlin
> // BlogRepository:
> fun findByAuthorIsNull(): List<BlogPost>  // Orphan blog yazıları
> // Admin panelinde: "Yazarı silinmiş yazılar" filtresi
> ```

#### AA-O11: appointment_services Tenant Filter Uyumluluk Notu
> **DİKKAT:** `AppointmentService` (pivot entity) `TenantAwareEntity`'den extend ediyor.
> `CascadeType.ALL` + `orphanRemoval = true` ile Hibernate Filter uyumluluğu test edilmeli.
> Cascade işlemleri sırasında tenant filter'ın child entity'lere uygulanıp uygulanmadığı
> integration test ile doğrulanmalıdır. Olası sorun: parent save → child insert'te
> tenant_id otomatik set edilmemesi.

#### AA-E9: Swagger/OpenAPI Tenant-Aware Yapılandırma
```kotlin
// Multi-tenant ortamda Swagger UI'da X-Tenant-ID header'ı gerekir.
@Configuration
class SwaggerConfig {
    @Bean
    fun customOpenApi(): OpenAPI = OpenAPI()
        .info(Info().title("Aesthetic Platform API").version("1.0"))
        .addSecurityItem(SecurityRequirement().addList("bearer-jwt"))
        .components(Components()
            .addSecuritySchemes("bearer-jwt", SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT"))
            .addParameters("X-Tenant-ID", Parameter()
                .`in`("header")
                .name("X-Tenant-ID")
                .description("Tenant subdomain slug (ör: salon1)")
                .required(false)  // Subdomain'den otomatik çözümlenir, manual test için
                .schema(StringSchema()))
        )
}
// NOT: Swagger UI üretimde devre dışı bırakılmalı (springdoc.swagger-ui.enabled=false)
```

#### AA-E10: Kafka / Event-Driven Uzun Vadeli Roadmap
> **Mevcut:** Tüm iletişim senkron veya `@Async` — mevcut ölçekte yeterli.
>
> **Gelecekte gerektiğinde:** Bildirim, audit log, webhook processing gibi işlemler
> event-driven mimariye (Kafka / RabbitMQ) taşınabilir:
> - `AppointmentCreatedEvent` → NotificationConsumer (bildirim gönder)
> - `AuditEvent` → AuditLogConsumer (audit log yaz)
> - `WebhookReceivedEvent` → PaymentConsumer (ödeme işle)
>
> Bu geçiş 50+ tenant veya yüksek throughput gerektiğinde değerlendirilmelidir.
> Şu an `@Async` + ThreadPoolTaskExecutor yeterlidir.

#### 16-1.1: UUID v7 + BINARY(16) Depolama
> **Mevcut:** `GenerationType.UUID` = UUID v4 (rastgele) + `VARCHAR(36)` (36 byte + index overhead)
>
> **Sorun:** UUID v4 rastgele olduğu için InnoDB Clustered Index'te fragmantasyona neden olur.
> Yeni kayıtlar rastgele page'lere yazılır → INSERT performansı düşer (büyük veri setlerinde ~%80).
>
> **Çözüm:** UUID v7 (zaman damgalı, sıralı) + `BINARY(16)` (16 byte — %56 küçük):
> ```kotlin
> // build.gradle.kts:
> // implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")
>
> // UUIDv7Generator.kt:
> import com.fasterxml.uuid.Generators
> class UUIDv7Generator : IdentifierGenerator {
>     private val generator = Generators.timeBasedEpochGenerator()
>     override fun generate(session: SharedSessionContractImplementor, obj: Any): Any {
>         return generator.generate()
>     }
> }
>
> // Entity'de kullanım:
> // @Id
> // @GeneratedValue(generator = "uuid-v7")
> // @GenericGenerator(name = "uuid-v7", strategy = "com.app.UUIDv7Generator")
> // @Column(columnDefinition = "BINARY(16)")
> // val id: UUID? = null
> ```
>
> **Migration stratejisi:** Yeni tablolar UUID v7 + BINARY(16), mevcut tablolar kademeli geçiş.
> Mevcut VARCHAR(36) UUID'ler `UNHEX(REPLACE(uuid, '-', ''))` ile dönüştürülebilir.

#### 16-1.2: Composite Index İyileştirme
> Composite indexler mevcut (migration'larda tanımlı).
>
> **İyileştirme:** `idx_appt_conflict` index'i optimize edilmeli:
> - Mevcut: `(tenant_id, staff_id, date, start_time, end_time, status)` — 6 sütun
> - Önerilen: `(tenant_id, staff_id, date)` — 3 sütun + status filtresi uygulamada
> - `status NOT IN (...)` negative condition MySQL optimizer'da index'i etkisiz kılabilir
>
> **Ek:** `@ElementCollection` tabloları için `(parent_id)` index FK ile oluşur,
> ancak multi-tenant sorgularda performans için `(tenant_id, parent_id)` composite gerekebilir.

#### 16-2.2: Noisy Neighbor Koruması
```kotlin
// DÜZELTME 16-2.2: Tenant-ID bazlı rate limiting (S3 userId bucket ile birleşik)
// Her paket için farklı API kota limiti:
enum class PlanRateLimit(val requestsPerMinute: Int) {
    STARTER(100),
    PROFESSIONAL(500),
    BUSINESS(1000),
    ENTERPRISE(2000)
}

// RateLimitFilter'a (§7.5) tenant bucket eklenmeli:
private val tenantBuckets: Cache<String, Bucket> = Caffeine.newBuilder()
    .maximumSize(5_000)
    .expireAfterAccess(Duration.ofMinutes(5))
    .build()

// doFilterInternal() içinde:
// val tenantId = TenantContext.getTenantId()
// val tenantBucket = tenantBuckets.get(tenantId) { createTenantBucket(plan.rateLimit) }
// if (!tenantBucket.tryConsume(1)) → 429 Too Many Requests

// NOT: DB connection pool tenant izolasyonu için HikariCP multi-pool veya
// pgBouncer tenant routing gelecekte düşünülebilir.
```

#### 16-3.1: Deadlock / Gap Lock Önleme — Redis Reservation
> **Mevcut:** `PESSIMISTIC_WRITE` lock + deadlock uyarısı (satır 1500, 1569).
>
> **Sorun:** Gap Lock riski hala mevcut — aynı aralığa eşzamanlı iki INSERT deadlock yapabilir.
>
> **Çözüm:** Redis tabanlı Reservation (Hold) mekanizması:
> ```
> SETNX tenant:{tenantId}:slot:{date}:{time}:{staffId} userId EX 300
> ```
> - Slotu 5 dakika geçici kilitle (kullanıcı ödeme/onay yapana kadar)
> - Süre dolunca otomatik serbest (Redis TTL)
> - DB'ye yazma sadece reservation sahibi yapabilir
> - Race condition: Redis SETNX atomic — ilk gelen alır
>
> **Akış:** Müşteri slot seçer → Redis SETNX → Başarılı → Form doldurur → DB INSERT → Redis DEL
>                               → Başarısız → "Slot başka biri tarafından seçildi" mesajı

#### 16-3.2: DST (Yaz Saati) Geçiş Uyarısı
> **Mevcut:** `ZonedDateTime` zaten kullanılıyor — doğru yaklaşım.
>
> **EKSİK DİKKAT:** DST geçişlerinde (Mart/Kasım) randevu saatleri 1 saat kayabilir.
> Spring `@Scheduled` cron job'ları UTC ile çalışır, tenant timezone'u ile uyumsuzluk olabilir.
>
> **Kontrol listesi:**
> 1. `@Scheduled(cron = ...)` → `zone = "UTC"` parametresi ekle (varsayılan JVM zone'a bağımlılığı kaldır)
> 2. Randevu oluşturmada `ZonedDateTime.of(date, time, zone)` kullanılıyor — DST geçişinde
>    "saat 02:30" gibi var olmayan saatler için `ZoneRules.getTransition()` kontrolü ekle
> 3. Hatırlatma job'larında (24h, 1h) hesaplanan zaman DST geçişi nedeniyle yanlış olabilir —
>    `Duration.between()` yerine `ChronoUnit.HOURS.between()` kullan

#### 16-3.3: Buffer Time Refinement
> **Mevcut:** `buffer_minutes` tek alan (Service entity) — hizmetler arası tampon süresi.
>
> **İyileştirme önerisi:** İki tip buffer ayrımı:
> - **Internal Buffer (Hizmetler arası):** Mevcut `bufferMinutes` — randevular arası boşluk
> - **Post-Service Buffer (Temizlik):** Hizmet sonrası oda/alan temizleme süresi
>
> ```kotlin
> // Service entity'ye ek alan:
> var postServiceBufferMinutes: Int = 0  // Hizmet sonrası temizlik süresi
> // Toplam buffer = bufferMinutes + postServiceBufferMinutes
> // AvailabilityService'te slot hesaplaması buna göre güncellenmeli
> ```

#### 16-4.1: Sağlık Verisi Şifreleme (AES-256 Encryption at Rest)
```kotlin
// DÜZELTME 16-4.1: Hasta notları (appointmentNotes) ve hassas alanlar şifresiz tutuluyor.
// Sadece SSL/TLS (transport encryption) mevcut — DB'de düz metin.

// EncryptedStringConverter.kt — JPA AttributeConverter
@Converter
class EncryptedStringConverter : AttributeConverter<String, String> {
    // Tenant başına benzersiz encryption key (tenant_encryption_keys tablosu)
    // Key rotation: Yeni key ile encrypt, eski key ile decrypt fallback

    override fun convertToDatabaseColumn(attribute: String?): String? {
        if (attribute == null) return null
        val tenantKey = EncryptionKeyProvider.getKeyForCurrentTenant()
        return AES256.encrypt(attribute, tenantKey)
    }

    override fun convertToEntityAttribute(dbData: String?): String? {
        if (dbData == null) return null
        val tenantKey = EncryptionKeyProvider.getKeyForCurrentTenant()
        return AES256.decrypt(dbData, tenantKey)
    }
}

// Kullanım (hassas alanlarda):
// @Convert(converter = EncryptedStringConverter::class)
// var appointmentNotes: String? = null

// tenant_encryption_keys tablosu:
// CREATE TABLE tenant_encryption_keys (
//     tenant_id VARCHAR(36) NOT NULL PRIMARY KEY,
//     encryption_key VARCHAR(255) NOT NULL,  -- AES-256 key (encrypted with master key)
//     key_version INT NOT NULL DEFAULT 1,
//     rotated_at TIMESTAMP(6) NULL,
//     FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
// );
```

#### 16-4.3: S3 Presigned URLs
```kotlin
// DÜZELTME 16-4.3: Public URL'ler yerine ömürlü Presigned URL kullanılmalı.
// Eski çalışanlara dosya erişimi kapanır, URL paylaşımı ile unauthorized access engellenir.

@Service
class StorageService(private val s3Client: S3Client) {
    fun getPresignedUrl(key: String, expirationMinutes: Int = 15): String {
        val presigner = S3Presigner.builder()
            .region(Region.EU_CENTRAL_1)
            .build()
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(expirationMinutes.toLong()))
            .getObjectRequest(getObjectRequest)
            .build()
        return presigner.presignGetObject(presignRequest).url().toString()
    }

    // Upload için de Presigned URL:
    fun getPresignedUploadUrl(key: String, contentType: String): String {
        // PutObjectPresignRequest ile benzer şekilde
        // Frontend doğrudan S3'e upload yapar (backend proxy gerekmez)
    }
}
// NOT: Mevcut public URL'ler kademeli olarak presigned'a geçirilmeli.
// Image URL'leri API response'larında presigned URL olarak dönülmeli.
```

#### 16-6.2: Zero-Downtime Migration
> **Sorun:** Büyük tablolara Flyway ile `ALTER TABLE ADD COLUMN` tabloyu dakikalarca kilitler.
>
> **Çözüm:** Online Schema Change araçları:
> - **gh-ost** (GitHub): Shadow table + binlog replication ile lock-free ALTER
> - **pt-online-schema-change** (Percona): Trigger-based approach
>
> ```bash
> # gh-ost örneği:
> gh-ost \
>     --host=db.app.com --database=aesthetic \
>     --table=appointments --alter="ADD COLUMN new_field VARCHAR(255)" \
>     --allow-on-master --execute
> ```
>
> **CI/CD entegrasyonu:**
> - Flyway migration'dan önce tablo boyutunu kontrol et
> - 1M+ satırlı tablolarda otomatik gh-ost kullan
> - Migration safety check: `SHOW TABLE STATUS` ile row count kontrol

#### 8-1: SubscriptionService.checkAndExpireTrial()
```kotlin
// DÜZELTME 8-1: Çağrı mevcut (satır 5942) ama metot tanımı eksikti.

@Service
class SubscriptionService(
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionModuleRepository: SubscriptionModuleRepository,
    private val tenantRepository: TenantRepository
) {
    companion object {
        private val logger = LoggerFactory.getLogger(SubscriptionService::class.java)
    }

    /**
     * Trial süresi dolan tenant'ları tespit et + abonelik durumunu EXPIRED'a güncelle.
     * ScheduledJobs.checkExpiredTrials() tarafından her gece 02:00'de çağrılır.
     */
    @Transactional
    fun checkAndExpireTrial(tenantId: String) {
        val subscription = subscriptionRepository.findByTenantId(tenantId) ?: return

        // Sadece TRIAL plan ve aktif abonelikleri kontrol et
        if (subscription.plan != SubscriptionPlan.TRIAL) return
        if (subscription.status != SubscriptionStatus.ACTIVE) return

        // Trial süresi dolmuş mu?
        if (subscription.endDate.isBefore(LocalDate.now())) {
            subscription.status = SubscriptionStatus.EXPIRED
            subscriptionRepository.save(subscription)

            // Tenant'ın aktif modüllerini devre dışı bırak (core hariç)
            val modules = subscriptionModuleRepository.findBySubscriptionId(subscription.id!!)
            modules.filter { !it.isCoreModule }.forEach { module ->
                module.isActive = false
                subscriptionModuleRepository.save(module)
            }

            logger.info("[tenant={}] Trial süresi doldu — abonelik EXPIRED olarak güncellendi", tenantId)
        }
    }
}
```

#### 8-2: PaymentService.processWebhookPayload()
```kotlin
// DÜZELTME 8-2: Çağrı mevcut (satır 5340) ama metot tanımı eksikti.
// PaymentService sınıfına eklenmeli:

/**
 * iyzico webhook payload'ını işle.
 * WebhookController.handleIyzicoWebhook() tarafından çağrılır.
 * Idempotency: processed_webhook_events tablosunda referans kodu kontrol edilir.
 */
@Transactional
fun processWebhookPayload(payload: String) {
    val json = objectMapper.readTree(payload)
    val eventType = json.get("eventType")?.asText()
        ?: throw IllegalArgumentException("eventType alanı bulunamadı")
    val referenceCode = json.get("token")?.asText()
        ?: json.get("paymentId")?.asText()
        ?: throw IllegalArgumentException("Referans kodu bulunamadı")

    // Idempotency check — aynı event tekrar işlenmesin
    if (processedWebhookEventRepository.existsByEventId(referenceCode)) {
        logger.info("Webhook event zaten işlenmiş — referenceCode={}", referenceCode)
        return
    }

    when (eventType) {
        "PAYMENT_SUCCESS" -> {
            val payment = paymentRepository.findByProviderPaymentId(referenceCode)
                ?: throw ResourceNotFoundException("Ödeme bulunamadı: $referenceCode")
            payment.status = PaymentStatus.COMPLETED
            payment.paidAt = Instant.now()
            paymentRepository.save(payment)

            // Aboneliği aktifleştir
            val subscription = subscriptionRepository.findById(payment.subscriptionId!!).orElseThrow()
            subscription.status = SubscriptionStatus.ACTIVE
            subscriptionRepository.save(subscription)
        }
        "PAYMENT_FAILURE" -> {
            val payment = paymentRepository.findByProviderPaymentId(referenceCode)
            payment?.let {
                it.status = PaymentStatus.FAILED
                paymentRepository.save(it)
            }
        }
        "SUBSCRIPTION_CANCELLED" -> {
            // İptal işlemi
            logger.info("Abonelik iptal webhook — referenceCode={}", referenceCode)
        }
        else -> logger.warn("Bilinmeyen webhook eventType: {}", eventType)
    }

    // İşlenen event'i kaydet (idempotency)
    processedWebhookEventRepository.save(ProcessedWebhookEvent().apply {
        this.eventId = referenceCode
        this.provider = "IYZICO"
        this.eventType = eventType
    })
}
```

---

## 27. Güncellenmiş Uygulama Yol Haritası

```
Faz B1: Proje İskeleti (1 hafta)
├── Spring Boot + Kotlin projesi oluştur
├── Gradle + tüm bağımlılıklar
├── application.yml (dev/staging/prod)
├── Docker Compose (MySQL + Redis)
├── Flyway migration'lar (tüm tablolar)
├── Temel entity'ler (TenantAwareEntity base class)
└── Repository katmanı

Faz B2: Multi-Tenant Altyapı (1 hafta)
├── TenantContext + TenantFilter (subdomain çözümleme)
├── TenantEntityListener (INSERT/UPDATE/DELETE koruması)  # Bölüm 2.3'te yeniden adlandırıldı
├── TenantAspect (SELECT koruması — Hibernate Filter)
├── TenantAwareTaskDecorator (async propagation)
├── TenantAwareScheduler (scheduled task'lar)
├── TenantAwareCacheKeyGenerator (Caffeine local cache)  # Bölüm 9/13'te düzeltildi
├── Tenant CRUD + Onboarding akışı
└── Tenant izolasyon testleri (cross-tenant saldırı testleri)

Faz B3: Auth & Güvenlik (1-2 hafta)
├── JWT token provider + refresh token rotation
├── Server-side token revocation (RefreshToken entity)
├── Spring Security konfigürasyonu
├── Cross-tenant JWT doğrulama
├── Brute force koruması (hesap kilitleme)
├── Rate limiting (Bucket4j)
├── Güvenli dosya yükleme (tip/boyut doğrulama)
├── CORS wildcard subdomain ayarı
├── Role-based access control (@PreAuthorize)
└── Auth testleri (brute force, cross-tenant, token expiry)

Faz B4: CRUD API'ler (2 hafta)
├── Service CRUD + ServiceCategory
├── Product CRUD (stok takibi dahil)
├── Blog CRUD (yayınla/taslak, yazar ilişkisi)
├── Gallery CRUD (Service ilişkisi düzeltilmiş)
├── Contact mesajları (okundu/yanıtlandı)
├── Site ayarları (timezone, iptal politikası dahil)
├── Review/değerlendirme sistemi
├── Client notes (müşteri notları)
├── Swagger UI dokümantasyonu (tenant-aware)
├── API versiyonlama (/api/v1/)
└── DTO'lar + mapper'lar + validation

Faz B5: Randevu Sistemi (2 hafta)
├── Appointment entity (çoklu hizmet desteği)
├── AppointmentService join entity (fiyat snapshot)
├── WorkingHours + BlockedTimeSlot
├── AvailabilityService (buffer time dahil slot hesaplama)
├── AppointmentService (READ_COMMITTED + PESSIMISTIC_WRITE)
├── Geçmiş tarih/saat validasyonu
├── İptal politikası (configurable saat)
├── Tekrarlayan randevu desteği (recurring)
├── Durum yönetimi (state machine + geçiş kuralları)
├── Dashboard istatistikleri
├── Çift randevu engelleme testleri (concurrency)
└── Waitlist (opsiyonel, v2)

Faz B6: Bildirimler & Background Jobs (1 hafta)
├── NotificationService (async + TenantAwareTaskDecorator)
├── E-posta entegrasyonu (SendGrid)
├── SMS entegrasyonu (Netgsm)
├── Bildirim template'leri (Mustache)
├── Randevu hatırlatıcıları (24h + 1h)
├── Değerlendirme isteği job'ı
├── Trial süre kontrolü job'ı
├── Retry mekanizması (spring-retry)
└── NotificationLog (gönderim takibi)

Faz B7: Ödeme & Abonelik (2 hafta)
├── Subscription entity + plan limitleri
├── Payment + Invoice entity'leri
├── iyzico entegrasyonu (Türkiye)
├── Webhook handler (ödeme sonucu)
├── Plan limit kontrolü (staff, randevu, depolama)
├── Fatura oluşturma (PDF)
├── Plan yükseltme/düşürme
└── Abonelik iptali

Faz B8: Reporting & Analytics (1 hafta)
├── Dashboard stats endpoint'i
├── Gelir raporları (günlük/haftalık/aylık)
├── Randevu istatistikleri
├── Personel performans metrikleri
├── Müşteri retention metrikleri
└── Excel/PDF dışa aktarma

Faz B9: Compliance & DevOps (1 hafta)
├── KVKK/GDPR: veri dışa aktarma + silme
├── Rıza yönetimi (ConsentRecord)
├── Audit log genişletme
├── CI/CD pipeline (GitHub Actions)
├── Nginx + TLS (Let's Encrypt)
├── MySQL prodüksiyon ayarları
├── Veritabanı yedekleme stratejisi
├── Sentry error tracking
├── Correlation ID + structured logging
├── Custom health indicators
├── Graceful shutdown
└── Monitoring (Prometheus + Grafana — opsiyonel)

Faz B10: Frontend Entegrasyonu (1 hafta)
├── Next.js API client güncelleme
├── Subdomain bazlı tenant çözümleme (frontend)
├── JWT auth flow (login/refresh/logout)
├── Admin panel → Spring Boot API geçişi
├── Public sayfa → Spring Boot API geçişi
└── E2E testler
```

**Toplam tahmin: ~13-14 hafta (3.5 ay)**
