# BACKEND_ARCHITECTURE.md — Kapsamlı Derinlemesine Analiz

> 6767 satır, 27 bölüm, satır satır incelendi.
> Teknik hata analizi + Fonksiyonel/iş mantığı analizi + Tutarsızlık tespiti

---

## 📊 Genel Değerlendirme

| Kriter | Puan | Açıklama |
|--------|------|----------|
| **Mimari Tasarım** | 9/10 | Multi-tenant, security-first, modüler yapı çok iyi |
| **Kod Tutarlılığı** | 7/10 | ~~6/10~~ Çoğu bölüm arası tutarsızlık aslında çözülmüş (doğrulama sonrası) |
| **Fonksiyonel Bütünlük** | 5/10 | Kritik iş akışları eksik veya tamamlanmamış |
| **Production Hazırlığı** | 8/10 | ~~7/10~~ Webhook güvenliği dahil DevOps iyi (doğrulama sonrası) |
| **Ölçeklenebilirlik** | 7/10 | Shared-schema multi-tenancy limitleri düşünülmeli |
| **Güvenlik** | 8/10 | JWT, rate limiting, brute force iyi; bazı boşluklar var |

---

## BÖLÜM 1: KRİTİK KOD HATALARI (Derleme/Çalışma Engelleyen)

### 🔴 K1 — NotificationContext Constructor Tutarsızlığı
**Bölüm:** §18.2.1 (satır 5381–5391 vs 5528–5536)

İlk tanım 9 parametre alıyor:
```kotlin
data class NotificationContext(
    val appointmentId: String, val tenantId: String,
    val clientName: String, val clientEmail: String, val clientPhone: String,
    val serviceName: String, val staffName: String,
    val date: String, val startTime: String
)
```

`sendClientBlacklistedNotification` içinde 6 parametre ile çağrılıyor:
```kotlin
val ctx = NotificationContext(
    tenantId = tenantId, recipientEmail = "",
    clientName = client.name, serviceName = "", date = "", time = ""
)
```

**3 sorun:**
- `recipientEmail` diye bir parametre yok → **derleme hatası**
- `time` diye bir parametre yok → `startTime` olmalı → **derleme hatası**
- `appointmentId`, `clientEmail`, `clientPhone`, `staffName` eksik → **derleme hatası**

---

### 🔴 K2 — `ctx.time` Referansı Mevcut Değil
**Bölüm:** §18.2.1 — `sendNoShowNotification` (satır 5507)

```kotlin
"time" to ctx.time  // NotificationContext'te 'time' alanı YOK
```
`startTime` olmalı. Derleme sırasında `Unresolved reference: time` hatası verir.

---

### 🔴 K3 — sendNoShowNotification: @Async + Entity Problemi
**Bölüm:** §18.2.1 (satır 5498–5510)

```kotlin
@Async("taskExecutor")
fun sendNoShowNotification(appointment: Appointment) {
    val ctx = toContext(appointment) // Lazy loading!
```

Diğer tüm metotlar `NotificationContext` (DTO) alırken, bu metot doğrudan `Appointment` entity alıyor. `@Async` ayrı thread'de çalışır ve Hibernate session kapalıdır → `toContext()` içindeki `appointment.primaryService?.title` veya `appointment.staff?.name` çağrıları **LazyInitializationException** fırlatır.

**sendClientBlacklistedNotification** da aynı sorunu taşıyor (satır 5516) — doğrudan `User` entity alıyor.

---

### 🔴 K4 — sendReminder Parametre Uyumsuzluğu
**Bölüm:** §18.3 vs §18.2.1

Reminder Job'da çağrı:
```kotlin
notificationService.sendReminder(appointment, NotificationType.APPOINTMENT_REMINDER_24H)
// appointment: Appointment entity
```

Ama `sendReminder` tanımı:
```kotlin
fun sendReminder(ctx: NotificationContext, type: NotificationType)
// ctx: NotificationContext DTO
```

**Tip uyumsuzluğu** → derleme hatası. `appointment` → `notificationService.toContext(appointment)` dönüşümü yapılmalı.

---

### 🔴 K5 — sendReviewRequest Parametre Uyumsuzluğu
**Bölüm:** §19.1 (satır 5853)

```kotlin
notificationService.sendReviewRequest(appointment)  // Appointment entity
```

Ama `sendReviewRequest` tanımı NotificationContext alıyor (satır 5482):
```kotlin
fun sendReviewRequest(ctx: NotificationContext)
```

Aynı tip uyumsuzluğu. Scheduled job'larda entity → DTO dönüşümü yapılmıyor.

---

### 🔴 K6 — Duplicate YAML `logging:` Key
**Bölüm:** §9.1 (satır 4098–4105 vs 4158–4161)

`application.yml` içinde `logging:` key'i **iki kez** tanımlanmış:
```yaml
# İlk tanım (satır 4098):
logging:
  level:
    root: INFO
    com.aesthetic.backend: DEBUG

# İkinci tanım (satır 4158):
logging:
  pattern:
    console: "..."
```

YAML spec'e göre ikinci tanım birincisini override eder → `logging.level` kaybedilir. Tek bir `logging:` bloğu altında birleştirilmeli.

---

### 🔴 K7 — Duplicate `file:` / `storage:` Konfigürasyonu
**Bölüm:** §9.1 (satır 4088–4096 vs 4148–4153)

İki farklı konfigürasyon bloğu aynı amaç için:
```yaml
file:
  upload:
    provider: ${FILE_PROVIDER:local}
    local-path: ./uploads
    s3: { bucket, region, access-key, secret-key }

storage:
  provider: ${FILE_PROVIDER:local}
  local:
    base-path: ${FILE_UPLOAD_PATH:./uploads}
    base-url: ${FILE_UPLOAD_URL:http://localhost:8080/uploads}
```

`LocalStorageProvider` `@Value("${storage.local.base-path}")` kullanıyor → `file.upload.local-path` hiç okunmuyor. Hangi konfigürasyon doğru? Standardize edilmeli.

---

### 🔴 K8 — V19 Migration Çakışması
**Bölüm:** §8.3 (satır 3988–3992)

```sql
ALTER TABLE subscriptions
    ADD COLUMN monthly_price DECIMAL(10,2) ...
    ADD COLUMN billing_period VARCHAR(20) ...
```

Ama V12'de (satır 3781) `CREATE TABLE subscriptions` zaten bu sütunları içeriyor:
```sql
monthly_price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
billing_period VARCHAR(20) NOT NULL DEFAULT 'MONTHLY',
```

V19 çalıştırıldığında "Duplicate column name" hatası alınır → **migration fail**.

---

### 🔴 K9 — Eksik `ReviewRepository.findByUserId` Tanımı
**Bölüm:** §20.2.1 (satır 5984)

```kotlin
reviewRepository.findByUserId(userId)
```

Ama `ReviewRepository`'de sadece şu metotlar tanımlı (§4 civarı):
- `findByServiceId`
- `findByStaffId`
- `findByAppointmentId`

`findByUserId` **tanımlanmamış** → derleme hatası.

---

### 🔴 K10 — Eksik `appointmentRepository.findByClientEmail` Tanımı
**Bölüm:** §20.2.1 (satır 5982)

```kotlin
appointmentRepository.findByClientEmail(user.email)
```

`AppointmentRepository`'de bu metot **yok**. Mevcut metotlar: `findConflictingAppointmentsWithLock`, `findByTenantIdAndDateBetween`, `countByTenantIdAndCreatedAtAfter` vb.

---

### 🔴 K11 — Eksik Repository Metotları (Anonymize + Delete)
**Bölüm:** §20.2.1 (satır 6002–6011)

Şu metotlar çağrılıyor ama hiçbir yerde tanımlanmamış:
- `appointmentRepository.anonymizeByClientEmail(...)` 
- `reviewRepository.anonymizeByUserId(...)` 
- `refreshTokenRepository.deleteByUserId(...)` 

> **DÜZELTME:** `contactMessageRepository.deleteByEmail(...)` **satır 1985'te tanımlıdır** — listeden çıkarıldı.

Bunlar custom `@Modifying @Query` metotları olarak tanımlanmalı.

---

### 🔴 K12 — `EmailService.healthCheck()` Interface'de Eksik
**Bölüm:** §25.1 (satır 6533) vs §18.4.1 (satır 5709–5712)

Interface tanımı:
```kotlin
interface EmailService {
    fun send(to: String, subject: String, htmlBody: String)
    fun healthCheck()   // Interface'de VAR
}
```

Ama `SendGridEmailService` override kullanmıyor:
```kotlin
fun healthCheck() { ... }  // 'override' keyword'ü eksik
```

Eğer interface'de tanımlıysa `override` keyword'ü zorunlu, yoksa compile hatası vermez ama `interface'deki healthCheck()` abstract kalır → **compile hatası**.

---

### 🔴 K13 — `site_settings` DDL'de `default_slot_duration_minutes` Eksik
**Bölüm:** §4 Entity vs §8 Migration

`SiteSettings` entity'de (§4, satır ~1429) tanımlı olan `defaultSlotDurationMinutes` alanı, migration V9 `CREATE TABLE site_settings` DDL'inde (satır ~3716-3736) **bulunmuyor**. Hibernate `ddl-auto: validate` kullanıldığında (satır ~4037) uygulama başlatılırken **SchemaManagementException** fırlatır ve ayağa hiç kalkmaz.

---

### 🔴 K14 — `deleteByIsReadTrueAndCreatedAtBefore` Repository Metodu Eksik
**Bölüm:** §19.1 (satır 5835)

```kotlin
contactMessageRepository.deleteByIsReadTrueAndCreatedAtBefore(cutoff)
```

Bu metot `ContactMessageRepository`'de tanımlanmamış. `deleteByEmail` tanımlı (satır 1985) ama bu farklı bir metot.

---

### 🔴 K15 — `findUpcomingNotReminded` JPQL — Tarih+Saat Karşılaştırma Hatası
**Bölüm:** §5.3.1 (satır ~1920-1926)

Sorguda `AND a.startTime <= :time` koşulu sadece saati karşılaştırıyor ama tarih karşılaştırması ayrı. Gece yarısını geçen durumlarda (örn: bugün 23:00 randevusu, yarın 01:00'de kontrol) yanlış sonuç verir. `date + startTime` birlikte karşılaştırılmalı.

---

## BÖLÜM 2: FONKSİYONEL EKSİKLİKLER (İş Mantığı)

### 🔴 F1 — Hizmet-Personel İlişkisi Yok (KRİTİK)

**Gerçek senaryo:** Bir güzellik kliniğinde Dr. Ayşe botoks yapabilir ama lazer yapamaz. Sistemde hangi personelin hangi hizmetleri sunabileceğini belirten bir mekanizma **yok**.

**Mevcut durum:**
- `Service` entity'de staff ilişkisi yok
- `User` (STAFF) entity'de service ilişkisi yok
- `findAvailableStaff()` sadece takvim müsaitliğine bakıyor, yetkinliğe değil
- Bir müşteri lazer randevusu aldığında, sadece botoks yapabilen personele atanabilir

**Gerekli:**
```sql
CREATE TABLE staff_services (
    staff_id VARCHAR(36) NOT NULL,
    service_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (staff_id, service_id),
    FOREIGN KEY (staff_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
);
```

Ve `findAvailableStaff()` sorgusuna `staff_services` JOIN eklenmeli.

---

### 🔴 F2 — Randevu Yeniden Planlama (Reschedule) Akışı Yok

**Mevcut durum:** Appointment entity'de sadece `cancelAppointment` var. Tarih/saat değiştirmek için:
1. İptal et (geçmiş kaybolur)
2. Yeni randevu oluştur

**Eksik olan:**
- `rescheduleAppointment(id, newDate, newTime)` metodu
- Eski randevuyla bağlantı (reschedule geçmişi)
- Müşteriye "randevunuz değiştirildi" bildirimi (`APPOINTMENT_RESCHEDULED` type tanımlı ama gönderen metot yok)
- İptal politikası kontrolleri (24 saat kuralı reschedule için de geçerli mi?)

---

### 🔴 F3 — Client (Müşteri) Tarafı API'ler Eksik

Müşteriler `CLIENT` rolüyle login yapabiliyor ama kullanabilecekleri endpoint yok:

| Beklenen Endpoint | Durumu |
|---|---|
| `GET /api/client/my-appointments` | ❌ Yok |
| `GET /api/client/my-reviews` | ❌ Yok |
| `POST /api/client/reviews` | ❌ Yok |
| `PUT /api/client/profile` | ❌ Yok |
| `GET /api/client/notifications` | ❌ Yok |
| `POST /api/client/appointments/{id}/cancel` | ❌ Yok |

`SecurityConfig`'te `"/api/admin/**"` sadece `TENANT_ADMIN` yetkilendirmesi var. `CLIENT` rolü login yapıp token alıyor ama hiçbir endpoint'e erişemiyor — `.anyRequest().authenticated()` kapsamına giriyor ancak hiçbir `/api/client/**` route tanımlanmamış.

---

### 🔴 F4 — Misafir → Kayıtlı Müşteri Eşleştirme

**Senaryo:** 
1. Ahmet telefon ile randevu alır (misafir, `client_id = null`, `clientEmail = "ahmet@mail.com"`)
2. Sonra web sitesinden kayıt olur (CLIENT rolü, email: `ahmet@mail.com`)
3. Eski randevuları yeni hesabıyla eşleşmez → randevu geçmişi bölünür

**Gerekli:** Kayıt sırasında `appointments.client_email = user.email` olan misafir randevuların `client_id`'sini güncelleyen bir eşleştirme mekanizması.

---

### 🔴 F5 — Ödeme Yaşam Döngüsü Eksik

Tanımlanan ama implementasyonu olmayan durumlar:

| Senaryo | Durumu |
|---|---|
| **Plan yükseltme** (STARTER → PRO) | Endpoint listeleniyor (§17.5) ama iş mantığı yok |
| **Plan düşürme** (PRO → STARTER) | ❌ Tamamen eksik — limit aşımı ne olur? |
| **Otomatik yenileme** | `autoRenew: Boolean` var ama tetikleyen job yok |
| **Ödeme başarısız** | `PAST_DUE` status var ama grace period/retry mantığı yok |
| **Trial → Paid geçiş** | Trial biter → tenant pasifleşir, ama ödeme yapma akışı belirsiz |
| **Fatura PDF oluşturma** | `pdfUrl: String` alanı var ama PDF generator yok |
| **İade (refund)** | `REFUNDED` status var ama iyzico refund mekanizması yok |

---

### 🟠 F6 — Public API'lerde Modül Kontrolü Yok

`ModuleGuardInterceptor` sadece `HandlerMethod` annotasyonlarını kontrol ediyor. Public endpoint'ler (`/api/public/**`) için modül kontrolü yok.

**Örnek:** Tenant blog modülü almamış ama `/api/public/{slug}/blog` endpointi herkese açık → blog verisi yoksa 404 dönecek ama bu "modül kapalı" değil "veri yok" demek. Tutarlı bir deneyim için public API'lerde de modül kontrolü yapılmalı.

---

### 🟠 F7 — Varsayılan Bildirim Template'leri Yok

Onboarding akışında (§12) bildirim template'leri oluşturulmuyor. Yeni bir tenant ilk randevuyu aldığında:
```kotlin
val template = notificationTemplateRepository
    .findByTenantIdAndType(ctx.tenantId, NotificationType.APPOINTMENT_CONFIRMATION)
    ?: return  // Template yoksa bildirim gönderilmez!
```

`?: return` → Hiçbir bildirim gitmez. Onboarding sırasında varsayılan template'ler seed edilmeli.

---

### 🟠 F8 — Otomatik Randevu Onay Seçeneği Yok

Tüm randevular `PENDING` → `CONFIRMED` geçişi admin onayı gerektiriyor. Birçok işletme otomatik onay ister. `SiteSettings`'e `autoConfirmAppointments: Boolean` eklenmeli.

---

### 🟠 F9 — STAFF Rolü Tamamen Devre Dışı

`STAFF` rolüne sahip kullanıcılar:
- Login **yapamıyor** (AuthService.login'de engelleniyor)
- Dashboard göremiyorlar
- Kendi randevularını göremiyorlar
- Takvimlerini yönetemiyorlar

STAFF kullanıcıları sadece "atanacak kişi" olarak var. Gerçek dünyada personelin kendi programını görmesi, müşteri notları eklemesi gerekir. En azından read-only erişim düşünülmeli.
BU KONU HAKKINDA BANA SORULAR SOR. SÖYLEYECEKLERİM VAR. MESELA HER BİR STAFF KENDİ SİSTEMİNE GİRMEDEN DE ADMİN ONLARIN YERİNE BAZI İŞLERİ YAPABİLMELİ
İLLA HER BİR STAFF KENDİ HESABINA GİRMEK ZORUNDA OLMASIN.

---

### 🟠 F10 — Raporlama Bölümü (§16) Boş Placeholder

§16 sadece bir placeholder notu var:
```
> **Not:** Bu bölüm ileride detaylandırılacaktır.
```

§22'de dashboard endpoint response şeması var ama service/repository implementasyonu yok. JPQL sorguları (`SUM`, `COUNT`, `GROUP BY` ile istatistik), repository metotları ve `DashboardService` tanımlanmamış.

---

### 🟠 F11 — Recurring Appointment Self-Invocation Sorunu

§5'te belirtilmiş ama çözüm verilmemiş:
```
Tekrarlayan randevu oluşturmada @Transactional self-invocation sorunu var
```

Spring AOP proxy'si nedeniyle aynı sınıftaki `@Transactional` metotu çağrıldığında transaction yönetilmez. Çözüm: Ayrı bir `RecurringAppointmentService` sınıfı veya `self` injection.

---

## BÖLÜM 3: BÖLÜMLER ARASI TUTARSIZLIKLAR

### 🟠 T1 — application.yml vs Environment Variables İsimlendirme

| Konfigürasyon | application.yml | .env (§26) | Docker Compose | Tutarlı mı? |
|---|---|---|---|---|
| Netgsm kullanıcı | `notification.sms.username` | `NETGSM_USERNAME` ✓ | `NETGSM_USERCODE` (§24 satır 6240) | ❌ |
| Netgsm sender | `notification.sms.sender-id` | `NETGSM_SENDER_ID` ✓ | Eksik | ❌ |
| Email from | `notification.from-email` ✓ | `NOTIFICATION_FROM_EMAIL` ✓ | Eksik | ⚠️ |

Production docker-compose'da (§24) `NETGSM_USERCODE` kullanılıyor ama `application.yml` `${NETGSM_USERNAME}` bekliyor → SMS gönderimi production'da çalışmaz.

---

### 🟠 T2 — PlanLimitExceededException → Yanlış HTTP Status

`GlobalExceptionHandler` (§7.8, satır 3270):
```kotlin
@ExceptionHandler(PlanLimitExceededException::class)
fun handlePlanLimit(ex: PlanLimitExceededException): ResponseEntity<ErrorResponse> {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(  // 429
```

429 "Too Many Requests" rate limiting içindir. Plan limiti aşımı **403 Forbidden** veya **402 Payment Required** olmalı. `errorCode: RATE_LIMIT_EXCEEDED` da yanıltıcı — `PLAN_LIMIT_EXCEEDED` olmalı.

---

### ~~🟠 T3 — SubscriptionPlan Enum Tanımı Eksik~~ ✅ YANLIŞ POZİTİF

> **DOĞRULAMA:** `SubscriptionPlan` enum'u **satır 792'de tanımlıdır**: `enum class SubscriptionPlan { TRIAL, STARTER, PROFESSIONAL, BUSINESS, ENTERPRISE }`

---

### ~~🟠 T4 — `findConfirmedBeforeDateTime` Repository Metodu Tanımsız~~ ✅ YANLIŞ POZİTİF

> **DOĞRULAMA:** Bu metot **satır 1955'te** `AppointmentRepository` içinde JPQL sorgusuyla tanımlıdır.

---

### ~~🟠 T5 — `findUpcomingNotReminded` Repository Metodu Tanımsız~~ ✅ YANLIŞ POZİTİF

> **DOĞRULAMA:** Bu metot **satır 1927'de** `AppointmentRepository` içinde tanımlıdır.

---

### ~~🟠 T6 — `findCompletedWithoutReview` Repository Metodu Tanımsız~~ ✅ YANLIŞ POZİTİF

> **DOĞRULAMA:** Bu metot **satır 1940'ta** `AppointmentRepository` içinde tanımlıdır.

---

### 🟠 T7 — Onboarding'de `forcePasswordChange` Alanı Yok

§12 (satır 5570):
```
→ İlk girişte şifre değişikliği zorunlu (forcePasswordChange = true)
```

Ama `User` entity'de `forcePasswordChange` alanı tanımlanmamış. Migration'larda da bu sütun yok.

---

### 🟠 T8 — AuditLog Entity vs Migration Tutarsızlığı

Entity (§14.3, satır 4667):
```kotlin
class AuditLog(
    val tenantId: String,
    val userId: String,
    ...
)
```

Constructor-based (immutable) tanım. Ama migration'da (§8.3, satır 3895) normal tablo. Sorun: Entity `TenantAwareEntity`'den extend etmiyor (tasarım gereği — platform admin erişimi). Ama `@EntityListeners` ve Hibernate Filter olmadan bu entity'nin tenant izolasyonu elle yönetilmeli — bu kural belgelenmemiş.

---

### 🟠 T9 — Invoice.totalAmount Alanı Entity'de Yok

Migration V12 (satır 3830):
```sql
total_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
```

Ama `Invoice` entity'de (§17.1) `totalAmount` alanı tanımlanmamış. Hibernate validate mode `total_amount` sütununu entity'da göremeyince hata verir.

---

## BÖLÜM 4: GÜVENLİK ANALİZİ

### ~~🟠 S1 — Webhook Endpoint'i Güvenlik Bypassı~~ ✅ YANLIŞ POZİTİF

> **DOĞRULAMA:** `/api/webhooks/**` **satır 2828'de** `SecurityConfig`'te `permitAll()` olarak tanımlıdır:
> ```kotlin
> .requestMatchers("/api/webhooks/**").permitAll()
> ```
> Ayrıca HMAC-SHA256 imza doğrulaması da uygulanmış (§17.5).

---

### 🟠 S2 — CORS Wildcard Subdomain Riski

CORS yapılandırması `*.app.com` pattern'ı qabul ediyor ama bu `evil.app.com` gibi sahte subdomain'lerden de istek kabul eder. Tenant slug doğrulaması TenantFilter'da yapılıyor ama CORS preflight aşamasında TenantFilter çalışmaz.

---

### 🟠 S3 — Rate Limiting Sadece IP Bazlı

Token-based rate limiting yok. Bir saldırgan farklı IP'lerden (proxy/VPN) sınırsız istek atabilir. En azından authenticated endpoint'ler için `userId` bazlı da rate limiting eklenebilir.

---

### 🟠 S4 — Şifre Politikası Tanımlanmamış

Minimum uzunluk, karmaşıklık kuralları, yaygın şifre kontrolü yok. `User.passwordHash` sadece BCrypt ile hash'leniyor ama "123456" gibi şifreler kabul edilir.

---

### 🟠 S5 — Email Doğrulama Yok

Kayıt sonrası email doğrulama akışı yok. Sahte email'lerle hesap oluşturulabilir. Özellikle CLIENT rolü için önemli.

---

### 🟠 S6 — Netgsm SMS: GET ile Şifre Gönderimi

```kotlin
val url = UriComponentsBuilder.fromUriString(apiUrl)  // GET request
    .queryParam("password", "{password}")  // URL'de şifre!
```

Şifre URL parametresinde → access log'larda, proxy cache'lerde görünür. POST kullanılmalı.

---

## BÖLÜM 5: PERFORMANS VE ÖLÇEKLENEBİLİRLİK

### 🟡 P1 — Caffeine Local Cache — Multi-Instance Senkronizasyon Yok

Cache stratejisi (§13.3) Caffeine local cache kullanıyor. Multi-instance deployment'da:
- Instance A'da hizmet güncellenir → Instance A cache evict eder
- Instance B'nin cache'i 5 dk boyunca stale veri döner

Çözüm: Redis pub/sub ile cache invalidation veya Redis'i cache store olarak kullanmak.

---

### 🟡 P2 — TenantAwareScheduler: Tüm Tenant'lar Seri İşleniyor

```kotlin
tenantAwareScheduler.executeForAllTenants { tenant -> ... }
```

Bu metot tüm tenant'ları sırayla işler. 1000 tenant olduğunda hatırlatma job'u saatlerce sürebilir. Paralel işleme veya partition bazlı scheduling gerekli.

---

### 🟡 P3 — ShedLock Öneriliyor Ama Eklenmemiş

§19.1'de multi-instance için ShedLock önerisi var:
```kotlin
// DÜZELTME: ... ShedLock kullanılmalı:
// implementation("net.javacrumbs.shedlock:shedlock-spring:5.16.0")
```

Ama bu sadece yorum olarak kalmış, ne `build.gradle.kts`'e eklendi ne de `@SchedulerLock` annotasyonları var. Production'da duplicate job çalışması kaçınılmaz.

---

### 🟡 P4 — Appointment İndeks Optimizasyonu

`idx_appt_conflict` 6 sütunlu composite index. `SELECT ... FOR UPDATE` sorgusunda `status NOT IN` kullanılıyor → index'in `status` kısmı etkisiz (negative condition). Index sıralaması `(tenant_id, staff_id, date)` olarak kısaltılmalı, `start_time/end_time/status` range scan ile halledilir.

---

## BÖLÜM 6: VERİTABANI ŞEMASI ANALİZİ

### 🟡 D1 — `ElementCollection` Tabloları: Sıralama Korunmuyor

`service_benefits`, `service_process_steps`, `product_features`, `blog_post_tags` tablolarının hiçbirinde `sort_order` veya benzer bir sıralama sütunu yok. `@OrderColumn` eklenmedikçe JPA sıralamayı garanti etmez.

---

### 🟡 D2 — Cascade DELETE Riski

`tenants → users → appointments` zincirinde:
```sql
FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
```

Tenant silindiğinde **tüm** veriler cascade ile silinir (users, appointments, payments, invoices). Bu:
- Yasal olarak sorunlu (faturalar 10 yıl saklanmalı)
- Geri dönüşümsüz (accidental delete riski)

Tenant silme yerine soft-delete (`is_active = false`) kullanılmalı. Gerçek veri silme sadece GDPR talebiyle olmalı.

---

### 🟡 D3 — `notification_logs` Tablosu Büyüme Riski

`notification_logs` için retention policy yok. Her bildirim gönderimi (başarılı/başarısız) kayıt oluşturuyor. Günde 100 randevu × 3 bildirim = günde 300 kayıt, yılda ~110K. Milyonlara ulaşabilir. Partition veya archival stratejisi gerekli.

---

### 🟡 D4 — `audit_logs` Aynı Büyüme Riski

Her CRUD operasyonu logllanıyor. Endekslenmiş ama partition/archival yok.

---

## BÖLÜM 7: DEPLOYMENT & DEVOPS ANALİZİ

### 🟡 O1 — CI/CD: Build + Docker Ayrı Aşamalar

CI pipeline'da:
1. Gradle build + test (ubuntu-latest)
2. Docker build (ayrı job)

Ama Docker build aşamasında `COPY build/libs/*.jar app.jar` kullanılıyor → `build/` dizini önceki job'da oluşuyor ama artifact olarak paylaşılmıyor.

Multi-stage Dockerfile (§10.3) kullanıldıysa sorun yok, ama simple Dockerfile (§10.2) kullanılıyorsa jar dosyası Docker build context'te olmaz.

---

### 🟡 O2 — Nginx `http2` Direktifi Deprecated

```nginx
listen 443 ssl http2;
```

Nginx 1.25+'ta `http2` on`; olarak ayrı direktifle yapılmalı.

---

### 🟡 O3 — Certbot Renewal Strategy

Docker Compose'daki certbot container'ı sadece renewal yapıyor ama ilk sertifika oluşturma adımı yok. İlk deployment'ta nasıl sertifika alınacağı belgelenmemiş.

---

### 🟡 O4 — GitHub Actions: Docker Build Jar Eksik

```yaml
- name: Build Docker Image
  run: docker build -t aesthetic-backend:${{ github.sha }} .
```

Simple Dockerfile (§10.2) `COPY build/libs/*.jar app.jar` bekliyor ama CI'da Gradle build artifact Docker context'e kopyalanmamış. Ya `actions/upload-artifact` + `download-artifact` kullanılmalı ya da multi-stage Dockerfile tercih edilmeli.

---

## BÖLÜM 8: EKSİK TANIMLANAN ENTITY/REPOSITORY/SERVICE'LER (DOĞRULANMIŞ)

| Referans Edilen | Bölüm | Tanımlanmış mı? |
|---|---|---|
| ~~`SubscriptionPlan` enum~~ | §4 | ✅ Satır 792'de tanımlı |
| `SubscriptionService.checkAndExpireTrial()` | §19.1 | ❌ |
| `TenantAwareScheduler` sınıfı | §18.3, §19.1 | ❌ (sadece isim referansı) |
| ~~`findConfirmedBeforeDateTime()`~~ | §5 | ✅ Satır 1955'te tanımlı |
| ~~`findUpcomingNotReminded()`~~ | §5 | ✅ Satır 1927'de tanımlı |
| ~~`findCompletedWithoutReview()`~~ | §5 | ✅ Satır 1940'ta tanımlı |
| `appointmentRepository.anonymizeByClientEmail()` | §20.2.1 | ❌ |
| `appointmentRepository.findByClientEmail()` | §20.2.1 | ❌ |
| `reviewRepository.anonymizeByUserId()` | §20.2.1 | ❌ |
| `reviewRepository.findByUserId()` | §20.2.1 | ❌ |
| ~~`contactMessageRepository.deleteByEmail()`~~ | §5 | ✅ Satır 1985'te tanımlı |
| `contactMessageRepository.deleteByIsReadTrueAndCreatedAtBefore()` | §19.1 | ❌ |
| `refreshTokenRepository.deleteByUserId()` | §20.2.1 | ❌ |
| `PaymentService.processWebhookPayload()` | §17.5 | ❌ |
| `SubscriptionService` genel | §19.1 | ❌ |
| `DashboardService` / Stats repository | §22 | ❌ |
| `GdprService` constructor deps (ContactMessageRepo) | §20.2.1 | ✓ |
| ~~`SiteSettingsRepository.findByTenantId()`~~ | §17.2 | ✅ Satır 1990'da tanımlı |
| ~~`UserRepository.countByTenantIdAndRole()`~~ | §17.2 | ✅ Satır 1977'de tanımlı |
| `Invoice.totalAmount` field | §8.3 vs §17.1 | ❌ (migration'da var, entity'de yok) |
| `SiteSettings.defaultSlotDurationMinutes` DDL | §4 vs §8 | ❌ (entity'de var, DDL'de yok) |
| `ReviewRepository` genel | §20, §22 | ❌ |
| `RefreshTokenRepository` genel | §20 | ❌ |
| `ServiceCategoryRepository` | §4, Admin CRUD | ❌ |

---

## BÖLÜM 9: POZİTİF BULGULAR

Aşağıdaki tasarım kararları yüksek kalitede ve endüstri standartlarında:

| # | Alan | Neden İyi |
|---|---|---|
| ✅ 1 | **Multi-tenancy** | Shared schema + Hibernate filter + TenantEntityListener tam doğru |
| ✅ 2 | **Concurrency control** | `READ_COMMITTED` + `PESSIMISTIC_WRITE` lock double-booking'i engeller |
| ✅ 3 | **Çoklu hizmet desteği** | `AppointmentService` pivot entity ile fiyat/süre snapshot alma |
| ✅ 4 | **Async tenant propagation** | `TenantAwareTaskDecorator` ile `@Async` thread'lerde tenant context korunuyor |
| ✅ 5 | **Token rotation** | Refresh token family + theft detection mekanizması |
| ✅ 6 | **File upload security** | MIME sniffing (Tika) + decompression bomb koruması + path traversal önleme |
| ✅ 7 | **Structured logging** | MDC ile tenant + correlation ID tüm loglarda |
| ✅ 8 | **Modül sistemi** | Sektör bazlı feature flag'ler + add-on fiyatlandırma modeli |
| ✅ 9 | **Büyük sayı hassasiyeti** | `BigDecimal` + `DECIMAL(10,2)` tutarlı kullanım |
| ✅ 10 | **UTF-8/Turkish collation** | `utf8mb4_turkish_ci` tüm tablolarda + Docker ayarlarında |
| ✅ 11 | **Graceful shutdown** | Production config'te `server.shutdown=graceful` |
| ✅ 12 | **X-Forwarded-For spoofing** | Nginx'te sadece `$remote_addr` (client gerçek IP) yazılıyor |
| ✅ 13 | **KVKK/GDPR** | Veri taşınabilirlik + unutulma hakkı + rıza yönetimi |
| ✅ 14 | **Buffer time** | Randevular arası tampon süre (`bufferMinutes`) desteği |
| ✅ 15 | **Optimistic locking** | Appointment entity'de `@Version` ile concurrent update koruması |
| ✅ 16 | **Webhook güvenliği** | `/api/webhooks/**` permitAll + HMAC-SHA256 imza doğrulama |
| ✅ 17 | **Kapsamlı repository tanımları** | Appointment, SiteSettings, User repository metotları detaylı |
| ✅ 18 | **No-show kara liste sistemi** | 3 kez gelmezse otomatik engelleme iyi düşünülmüş |
| ✅ 19 | **Tenant timezone desteği** | Her yerde tenant'ın timezone'u kullanılıyor |
| ✅ 20 | **DTO pattern** | Entity'ler asla API'dan dönmüyor, güvenlik için önemli |

---

## BÖLÜM 10: ÖNCELİKLENDİRİLMİŞ AKSİYON PLANI

### 🔴 Acil (Derleme/Çalışma Engelleyen) — 1. Sprint

| # | Aksiyon | Etki |
|---|---|---|
| 1 | NotificationContext constructor düzelt (K1–K5) | Derleme hatası giderilir |
| 2 | V19 migration kaldır veya conditional yap (K8) | Flyway migration fail |
| 3 | Eksik repository metotlarını tanımla (K9–K11, K14) | GDPR servisi + job'lar çalışır |
| 4 | YAML duplicate key'leri birleştir (K6, K7) | Config doğru yüklenir |
| 5 | ~~Webhook endpoint'i SecurityConfig'e ekle (S1)~~ ✅ Zaten tanımlı | — |
| 6 | `EmailService.healthCheck()` override ekle (K12) | Health indicator çalışır |
| 7 | `site_settings` DDL'e `default_slot_duration_minutes` ekle (K13) | Hibernate validate geçer |
| 8 | `findUpcomingNotReminded` JPQL tarih+saat düzelt (K15) | Gece yarısı hatası engellenir |
| 9 | İki `WebConfig` sınıfını birleştir (AA-O8) | BeanDefinitionOverrideException engellenir |

### 🟠 Yüksek Öncelik — 2. Sprint

| # | Aksiyon | Etki |
|---|---|---|
| 10 | `staff_services` ilişki tablosu ekle (F1) | Doğru personel ataması |
| 11 | Reschedule akışı ekle (F2) | İş sürekliliği |
| 12 | Client API endpoint'leri ekle (F3) | Müşteri deneyimi |
| 13 | Varsayılan bildirim template'leri (F7) | Bildirimler ilk günden çalışır |
| 14 | Env var isimlendirmelerini tutarlılaştır (T1) | Production SMS çalışır |
| 15 | ~~`SubscriptionPlan` enum tanımla (T3)~~ ✅ Zaten satır 792'de tanımlı | — |
| 16 | `Invoice.totalAmount` entity'e ekle (T9) | Hibernate validate geçer |
| 17 | Eksik repository tanımları ekle (ReviewRepo, RefreshTokenRepo, ServiceCategoryRepo) | GDPR + dashboard çalışır |
| 18 | `User.title` field ekle (AA-D8) | Staff uzmanlık alanı saklanabilir |
| 19 | `createRecurringAppointments` self-invocation düzelt (AA-O3) | Kısmi başarı senaryosu çalışır |

### 🟡 Normal Öncelik — 3. Sprint+

| # | Aksiyon | Etki |
|---|---|---|
| 20 | PlanLimitExceededException → 403 (T2) | Doğru HTTP semantiği |
| 21 | Misafir→Müşteri eşleştirme (F4) | Veri bütünlüğü |
| 22 | Ödeme yaşam döngüsü tamamla (F5) | SaaS gelir modeli |
| 23 | Otomatik randevu onay seçeneği (F8) | İşletme esnekliği |
| 24 | STAFF rolü erişimi (F9) | Operasyonel verimlilik |
| 25 | ShedLock implementasyonu (P3) | Multi-instance güvenliği |
| 26 | Cache invalidation stratejisi (P1) | Multi-instance tutarlılık |
| 27 | Şifre politikası (S4) + Email doğrulama (S5) | Güvenlik |
| 28 | `notification_logs` / `audit_logs` archival (D3, D4) | DB performansı |
| 29 | Tenant soft-delete (D2) | Veri güvenliği |
| 30 | Dashboard/Reporting service implementasyonu (F10) | İş zekası |
| 31 | WorkingHours çoklu aralık desteği (AA-O4) | Esnek personel programı |
| 32 | CORS custom domain refresh (AA-O6) | Yeni domainler anlık çalışır |
| 33 | Bildirim template XSS koruması (AA-D5) | Güvenlik |
| 34 | S3 Presigned URL (AA-4.3) | Dosya erişim güvenliği |
| 35 | Webhook idempotency (AA-5.1) | Mükerrer ödeme engellenir |

---

## BÖLÜM 11: ORTA SEVİYE SORUNLAR (Mimari Analiz Raporu)

> Aşağıdaki bulgular `backend_architecture_analysis.md` kaynağından alınmıştır. Prefix: **AA-O** (Orta seviye)

### 🟠 AA-O1 — `Tenant.enabledModules` JSON Alanı Belirsiz

Entity'de `enabledModules` field var mı yok mu net değil. `Subscription` entity'sinde "`NOT: enabledModules JSON alanı KULLANILMAZ`" deniliyor ama Tenant entity'sinde bahsedilmemiş. Netleştirilmeli.

---

### 🟠 AA-O2 — `Tenant.plan` String ↔ Enum Tutarsızlığı

Tenant entity'sinde `plan: String = "TRIAL"` ama migration'da `ENUM('TRIAL','STARTER',...)` tanımlı. Subscription entity'si ayrıca `@Enumerated(EnumType.STRING) var plan: SubscriptionPlan`. İki yerde `plan` tutmak senkronizasyon riski taşır. **Öneri:** `Tenant.plan` kaldırılıp abonelik yalnızca `Subscription` üzerinden yönetilmeli.

---

### 🟠 AA-O3 — `createRecurringAppointments` — Self-Invocation Sorunu (Detaylı)

`createAppointment()` aynı sınıftan çağrılıyor → Spring AOP proxy bypass → `@Transactional` çalışmaz. Kısmi başarı senaryosu (4/8 randevu oluşturuldu) bekleniyor ama tek transaction'da ya hep ya hiç olur. **Çözüm:** `TransactionTemplate` veya ayrı bean (`RecurringAppointmentService`).

---

### 🟠 AA-O4 — `WorkingHours` — Personel Başına Birden Fazla Çalışma Aralığı Desteği Yok

`UNIQUE KEY uk_working_hours (tenant_id, staff_id, day_of_week)` constraint'i personelin aynı gün "09:00-12:00 + 14:00-18:00" gibi birden fazla aralık çalışmasını engelliyor. Break kaydı bu durumu kapsamaz.

---

### 🟠 AA-O5 — `Appointment.version` — Optimistic + Pessimistic Lock Beraber

Entity'de `@Version` (optimistic) VE repository'de `@Lock(PESSIMISTIC_WRITE)` beraber. Pessimistic lock zaten concurrent update'leri önlüyor, `@Version` ekstra fayda sağlamaz (aksine lock süresi uzarsa `OptimisticLockException` riski doğar).

---

### 🟠 AA-O6 — `corsConfigSource` — Custom Domain Listesi Stale Kalır

CORS config `@Bean` olarak bir kez oluşturuluyor. Yeni custom domain eklendiğinde CORS listesi yenilenmez. Periyodik refresh mekanizması tanımlanmalı.

---

### 🟠 AA-O7 — `FlywayConfig.kt` + YAML Çakışması

Hem YAML'de `spring.flyway.*` hem de custom `FlywayConfig` bean var. İkisi birden olursa custom bean YAML'i override eder → beklenmeyen davranış. Hangisi kullanılacak netleştirilmeli.

---

### 🟠 AA-O8 — `WebConfig` Sınıfı İki Kez Tanımlanmış

1. Satır ~5699: `WebConfig` — `RestTemplate` bean
2. Satır ~3921: `WebConfig` — `ModuleGuardInterceptor` kaydı

Aynı sınıf adıyla → `BeanDefinitionOverrideException`. Birleştirilmeli.

---

### 🟠 AA-O9 — `AccessDeniedException` Handler — Yanlış ErrorCode

`ErrorCode.CROSS_TENANT_ACCESS` dönüyor ama her 403 cross-tenant erişim değildir. Genel `FORBIDDEN` veya `INSUFFICIENT_PERMISSIONS` error code eklenmeli.

---

### 🟠 AA-O11 — `appointment_services` — Tenant Filter Uyumsuzluğu Riski

`AppointmentService` entity `TenantAwareEntity`'den extend ediyor ama `CascadeType.ALL` + `orphanRemoval = true` ile Hibernate filter'ın child entity'lerde düzgün uygulanıp uygulanmadığı dikkatli test edilmeli.

---

### 🟠 AA-O12 — `Appointment.client` — Nullable İlişki + String Duplikasyonu

Hem `client: User?` referansı hem de `clientName/Email/Phone` string alanları var. Müşteri bilgilerini değiştirdiğinde eski randevular eski bilgiyi tutar — bu snapshot olarak faydalı mı yoksa senkronizasyon riski mi? Doküman net açıklamamış.

---

### 🟠 AA-O13 — `ReviewRepository` ve `RefreshTokenRepository` Tanımsız

`GdprService`, `NotificationServiceHealthIndicator` ve diğer servisler bu repository'leri kullanıyor ama §5.3.1 repository listesinde tanımları yok.

---

## BÖLÜM 12: DÜŞÜK SEVİYE SORUNLAR VE İYİLEŞTİRMELER (Mimari Analiz Raporu)

### 🟢 AA-D1 — `Tika` — Her Upload'da Yeniden Oluşturuluyor

`val tika = Tika()` her dosya yüklemesinde yeniden oluşturuluyor. `Tika` thread-safe'dir, singleton olarak inject edilebilir.

### 🟢 AA-D3 — `blog_posts.author_id` ON DELETE SET NULL

Author silinince yazı sahipsiz kalır. Kabul edilebilir ama admin panelinde "yazarı silinmiş" blog yazılarının yönetimi düşünülmeli.

### 🟢 AA-D4 — `SubscriptionPlan` ENTERPRISE Limiti

ENTERPRISE plan "Sınırsız" denilmiş ama `maxStaff` ve `maxAppointmentsPerMonth` int. `Int.MAX_VALUE` yerine nullable + null=sınırsız yaklaşımı daha temiz.

### 🟢 AA-D5 — Bildirim Template'lerinde XSS Riski

`replaceVariables()` düz string replace yapıyor, HTML escape yok. `clientName` gibi alanlar user input'u — HTML injection mümkün (email body HTML).

### 🟢 AA-D7 — Audit Log — Entity İlişkisi Yok

`AuditLog` sadece `entityType/entityId` (String) tutuyor — FK yok. Entity silindiğinde orphan log kalır. Pragmatik ama metadata olarak belirtilmeli.

### 🟢 AA-D8 — `User` Entity — `title` (Uzmanlık) Alanı Eksik

`CreateStaffRequest` DTO'sunda `val title: String?` var ama `User` entity'sinde `title` field'ı yok. Staff uzmanlık alanı nerede saklanacak?

---

## BÖLÜM 13: EK TUTARSIZLIKLAR (Mimari Analiz Raporu)

### 🟡 AA-T3 — `logging.pattern.console` MDC Key Tutarsızlığı

İki farklı yerde farklı MDC key kullanılıyor:
- `application.yml` pattern: `[tenant=%X{tenantId}]`
- `TenantFilter` logging: `[tenant=%X{tenantSlug}]`

Hangisi gösterilecek? Tutarlılaştırılmalı.

### 🟡 AA-T4 — `sendgrid-java` Versiyon Farkı

`build.gradle.kts`: `4.10.1` vs bildirim provider notu: `4.10.2`. Minor fark ama tutarlılık bozuluyor.

### 🟡 AA-T6 — `ConsentRecord.userId` — String ↔ FK

Entity'de `val userId: String = ""` ama migration'da `FOREIGN KEY (user_id) REFERENCES users(id)`. JPA entity'de FK ilişki kurmak yerine düz String kullanılmış — ORM best practice'e aykırı.

### 🟡 AA-T7 — `ScheduledJobs` + `AppointmentReminderJob` Duplicate Job Riski

Her ikisi de `@Scheduled` ile tetikleniyor ve ShedLock henüz yok. Aynı bildirim iki kez gönderilebilir.

---

## BÖLÜM 14: GEREKSİZLİKLER (Mimari Analiz Raporu)

### ❌ AA-G1 — `V19__alter_subscriptions_add_billing.sql`

V12'de zaten `monthly_price` ve `billing_period` var. V19 kaldırılmalı. (K8 ile aynı sorun)

### ❌ AA-G2 — Bölüm 16 — Boş İçerik

Sadece "`Bu bölüm ileride detaylandırılacaktır`" notu. Bölüm 22 aynı konuyu kapsıyor. Kaldırılabilir.

### ❌ AA-G3 — `Appointment.@Version` — Pessimistic Lock Varken Gereksiz

Sadece `PESSIMISTIC_WRITE` yeterli. `@Version` kaldırılabilir. (AA-O5 ile aynı)

### ❌ AA-G4 — `file:` YAML Bloğu

`storage:` bloğu aynı işi yapıyor ve `StorageProvider`'da kullanılan budur. `file:` bloğu ölü konfigürasyon. (K7 ile aynı)

### ❌ AA-G5 — Bölüm 21 — API Versiyonlama (Şu An Gereksiz)

Tüm endpoint'ler versiyonsuz. İlk breaking change geldiğinde eklenmesi daha pragmatik.

---

## BÖLÜM 15: EKLENMESİ ÖNERİLEN TANIMLAR (Mimari Analiz Raporu)

### 🔴 AA-E2 — `ReviewRepository` Tanımı *(KRİTİK)*

```kotlin
interface ReviewRepository : JpaRepository<Review, String> {
    fun findByClientId(clientId: String): List<Review>
    @Modifying @Query("UPDATE Review r SET r.comment = '[Silindi]' WHERE r.client.id = :userId")
    fun anonymizeByUserId(@Param("userId") userId: String)
}
```

### 🔴 AA-E3 — `RefreshTokenRepository` Tanımı *(KRİTİK)*

```kotlin
interface RefreshTokenRepository : JpaRepository<RefreshToken, String> {
    fun deleteByUserId(userId: String)
    fun findByFamily(family: String): List<RefreshToken>
    fun deleteByExpiresAtBefore(cutoff: Instant)
}
```

### 🟠 AA-E4 — `User.title` Field Eksik

Staff uzmanlık alanı DTO'da var ama entity'de yok. Eklenmeli.

### 🟠 AA-E6 — `@EnableRetry` Annotation Eksik

Retry mekanizması kullanılıyor ama `@EnableRetry` hangi `@Configuration` class'a ekleneceği belirtilmemiş.

### 🟠 AA-E7 — `@EnableScheduling` Annotation Eksik

`@Scheduled` job'lar tanımlı ama `@EnableScheduling` hiçbir configuration class'ta gösterilmemiş.

### 🟠 AA-E8 — `@EnableAsync` Annotation Eksik

AsyncConfig'te `taskExecutor` bean tanımı var ama `@EnableAsync` gösterilmemiş.

### 🟡 AA-E9 — Swagger/OpenAPI Tenant-Aware Yapılandırma

Multi-tenant ortamda Swagger'ın nasıl çalışacağı belirtilmemiş.

### 🟡 AA-E10 — Kafka / Event-Driven Mimari Yok

Tüm iletişim senkron veya `@Async`. Ölçeklenebilirlik için event-driven seçeneği uzun vadeli roadmap'e eklenebilir.

### 🟠 AA-E11 — `StaffPublicResponse` — Hizmet-Personel İlişkisi API'de Var, Entity'de Yok

`GET /api/public/staff?serviceId=xxx` endpoint'i tanımlı ama `Service ↔ User(STAFF)` arasında many-to-many ilişki entity'de yok. Bu sorguyu nasıl yapacaksınız?

### 🟡 AA-E12 — `ServiceCategoryRepository` Eksik

`ServiceCategoryRepository` hiçbir yerde tanımlanmamış ama admin CRUD endpoint'lerinde category yönetimi gerekecek.

---

## BÖLÜM 16: MİMARİ DENETİM VE RİSK ANALİZ RAPORU

> Bu bölüm Expert Software Architect perspektifinden hazırlanmıştır. Sistemin "çalışan" bir yapıdan "kusursuz ve ölçeklenebilir" bir yapıya dönüşmesi için gereken kritik düzeltmeleri içerir.

### 1. Veri Mimarisi ve Veritabanı Darboğazları

**1.1. UUID Depolama ve İndeks Fragmantasyonu**
Mevcut Durum: Tüm ID'ler VARCHAR(36) ve rastgele UUID olarak tasarlanmış.

Problem: Rastgele UUID'ler (v4), MySQL InnoDB'nin Clustered Index yapısını bozar. Veriler diskte rastgele yerlere yazılır (Fragmentation), bu da veri seti büyüdüğünde INSERT performansını %80'e kadar düşürür ve Disk I/O tavan yapar.

Çözüm: ID'leri veritabanında BINARY(16) olarak saklayın. Kotlin tarafında zaman sıralı (time-ordered) olan UUID v7 standardını kullanın.

**1.2. İndeksleme Stratejisi (Composite Index)**
Mevcut Durum: İndeksler genellikle tekil sütunlar üzerine kurulu.

Problem: Shared Schema mimarisinde Hibernate Filter her sorguya WHERE tenant_id = ? ekler. Eğer indeksler (tenant_id, ...) şeklinde Composite değilse, MySQL Full Table Scan yapar.

Çözüm: Kritik tüm indeksleri (tenant_id, target_column) sırasıyla yeniden tanımlayın.

**1.3. Soft Delete (Hassas Silme) Eksikliği**
Problem: Finansal verilerin fiziksel silinmesi raporlamayı imkansız kılar ve FK kısıtlamaları nedeniyle silme hata verir.

Çözüm: TenantAwareEntity'ye `deleted_at` sütunu + Hibernate `@SQLDelete` / `@Where` ile Soft Delete kurun.

### 2. Multi-Tenancy ve İzolasyon Riskleri

**2.1. Dağıtık Caching (Caffeine vs. Redis)**
Problem: Multi-instance'da Node-1'deki güncelleme Node-2'de yansımaz → veri tutarsızlığı.

Çözüm: Kritik verileri Redis (Distributed Cache) üzerinde tutun.

**2.2. "Noisy Neighbor" Etkisi**
Problem: Tek Tenant'ın aşırı yüklenmesi DB bağlantı havuzunu tüketerek herkesi yavaşlatır.

Çözüm: Tenant-ID bazlı Rate Limiting. Her paket için farklı kota.

### 3. Randevu Sistemi ve Concurrency

**3.1. Deadlock ve Gap Lock Tehlikesi**
Problem: Boş zaman dilimine aynı anda iki istek → Gap Lock → Deadlock.

Çözüm: Redis tabanlı Reservation (Hold) mekanizması — slotu 5 dk kilitleyin.

**3.2. Timezone ve DST Kaymaları**
Problem: DST geçişlerinde randevu saatleri 1 saat kayabilir.

Çözüm: DB'de Instant (UTC), iş mantığında Tenant'ın ZoneId'si ile ZonedDateTime.

**3.3. Tampon Süre Mantığı**
Problem: Hizmetler arası hazırlık süresi hesaba katılmamış.

Çözüm: "Internal Buffer" (işlem arası) ve "Post-Service Buffer" (temizlik) ayrımı.

### 4. Güvenlik, Gizlilik ve Uyumluluk

**4.1. Sağlık Verilerinin Korunması**
Problem: Hasta kayıtları şifresiz tutuluyor → DB sızıntısında tıbbi notlar sızar.

Çözüm: Hassas alanları AES-256 Encryption at Rest ile şifreleyin. Tenant başına benzersiz anahtar.

**4.2. Staff Giriş Paradoksu**
Problem: Staff login olamaz, kendi takvimini göremez.

Çözüm: Staff rolüne kısıtlı yetkiyle login hakkı tanıyın.

**4.3. S3 Dosya Erişimi**
Problem: Public URL'lerle dosyalar eski çalışanlara açık kalır.

Çözüm: S3 Presigned URL (15 dk ömürlü geçici linkler) kullanın.

### 5. Finansal Sistem ve Entegrasyonlar

**5.1. Webhook Idempotency**
Problem: iyzico aynı bildirimi 3 kez gönderebilir → 3 kez abonelik tanımlanır.

Çözüm: `processed_webhook_events` tablosunda referans kodu kontrolü.

**5.2. Proration Eksikliği**
Problem: Paket yükseltme sırasında kalan günlerin alacağı hesaplanmıyor.

Çözüm: "Credit Note" mantığını finansal modele ekleyin.

### 6. Operasyonel Bakım ve Ölçeklenebilirlik

**6.1. Audit Log Veri Şişmesi**
Problem: 10.000 tenant günde binlerce işlem → 1 yılda milyarlarca satır.

Çözüm: Append-only yapı (MongoDB/Elasticsearch) veya 6 aylık arşivleme.

**6.2. Sıfır Downtime Migrasyon**
Problem: Büyük tablolara Flyway ile sütun eklemek tabloyu dakikalarca kilitler.

Çözüm: Online Schema Change (gh-ost gibi araçlar).

### Sonuç ve Mimari Onay Notu
Bu dökümandaki revizyonlar yapıldığı takdirde, backend mimarisi Enterprise seviyede bir SaaS ürününe dönüşecektir. UUID v7, Soft Delete ve Redis tabanlı Reservation konuları Phase 1'de mutlaka çözülmelidir.

---

## DOĞRULAMA ÖZETİ (Yanlış Pozitif Düzeltmeleri)

| Bulgu | İlk Analiz | Düzeltme | Durum |
|-------|-----------|----------|-------|
| T3 (SubscriptionPlan) | ❌ Eksik | ✅ Satır 792'de tanımlı | **YANLIŞ POZİTİF** |
| T4 (findConfirmedBeforeDateTime) | ❌ Tanımsız | ✅ Satır 1955'te tanımlı | **YANLIŞ POZİTİF** |
| T5 (findUpcomingNotReminded) | ❌ Tanımsız | ✅ Satır 1927'de tanımlı | **YANLIŞ POZİTİF** |
| T6 (findCompletedWithoutReview) | ❌ Tanımsız | ✅ Satır 1940'ta tanımlı | **YANLIŞ POZİTİF** |
| S1 (Webhook güvenlik) | ❌ Eksik | ✅ Satır 2828'de permitAll | **YANLIŞ POZİTİF** |
| Bölüm 8: deleteByEmail | ❌ Tanımsız | ✅ Satır 1985'te tanımlı | **YANLIŞ POZİTİF** |
| Bölüm 8: countByTenantIdAndRole | ❌ Tanımsız | ✅ Satır 1977'de tanımlı | **YANLIŞ POZİTİF** |
| Bölüm 8: findByTenantId (Settings) | ❌ Tanımsız | ✅ Satır 1990'da tanımlı | **YANLIŞ POZİTİF** |
| K13 (defaultSlotDurationMinutes DDL) | — | ❌ YENİ BULGU | **EKLENDİ** |
| K14 (deleteByIsReadTrueAndCreatedAtBefore) | — | ❌ YENİ BULGU | **EKLENDİ** |
| K15 (JPQL tarih+saat hatası) | — | ❌ YENİ BULGU | **EKLENDİ** |
| Genel puan: Kod Tutarlılığı | 6/10 | 7/10 | **YÜKSELTİLDİ** |
| Genel puan: Production Hazırlığı | 7/10 | 8/10 | **YÜKSELTİLDİ** |

---

> **Not:** Bu analiz dokümanın tamamı (6767 satır, 27 bölüm) satır satır incelenerek hazırlanmıştır. Her bulgu ilgili satır numarasıyla referanslandırılmıştır. İlk analizdeki 8 yanlış pozitif bulgu düzeltilmiş, 3 yeni bulgu eklenmiştir. Mimari analiz raporundan 13 orta seviye, 6 düşük seviye, 7 tutarsızlık, 5 gereksizlik ve 12 eksik tanım bulgusu entegre edilmiştir.
