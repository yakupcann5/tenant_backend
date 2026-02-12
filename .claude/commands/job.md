Scheduled job oluştur: $ARGUMENTS

## Format

`$ARGUMENTS` formatı: `JobAdı cron:CRON_EXPR` veya `JobAdı rate:MILLIS`

Örnekler:
- `CleanupExpiredCoupons cron:0 0 4 * * ?`
- `SyncInventory rate:300000`

## Kurallar

### 1. Argümanları parse et

- `JobAdı`: İlk kelime (PascalCase)
- `cron:`: Cron expression (6 alan: saniye dakika saat gün ay haftanınGünü)
- `rate:`: Fixed rate milisaniye cinsinden

### 2. Dosya konumu

`src/main/kotlin/com/aesthetic/backend/job/{JobAdı}.kt`

Veya mevcut `ScheduledJobs.kt` varsa ona metot ekle.

### 3. Job şablonu

```kotlin
package com.aesthetic.backend.job

import com.aesthetic.backend.tenant.TenantAwareScheduler
import com.aesthetic.backend.usecase.SettingsService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.ZoneId

@Component
class {JobAdı}(
    private val tenantAwareScheduler: TenantAwareScheduler,
    private val settingsService: SettingsService,
    // İş mantığı için gerekli servisler
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Cron kullanımı:
    @Scheduled(cron = "{CRON_EXPR}")
    // VEYA fixed rate kullanımı:
    // @Scheduled(fixedRate = {MILLIS})
    @SchedulerLock(
        name = "{jobAdıCamelCase}",
        lockAtLeastFor = "4m",
        lockAtMostFor = "10m"
    )
    fun execute() {
        logger.info("{JobAdı} başlatıldı")

        tenantAwareScheduler.executeForAllTenants { tenant ->
            try {
                val settings = settingsService.findByTenantIdOrNull(tenant.id!!)
                val tenantZone = ZoneId.of(settings?.timezone ?: "Europe/Istanbul")

                // İş mantığı buraya
                // Her item için ayrı try-catch:
                // items.forEach { item ->
                //     try {
                //         processItem(item)
                //     } catch (e: Exception) {
                //         logger.error("[tenant={}] Item işleme hatası: {}", tenant.slug, e.message, e)
                //     }
                // }

                logger.info("[tenant={}] {JobAdı} tamamlandı", tenant.slug)
            } catch (e: Exception) {
                logger.error("[tenant={}] {JobAdı} hatası: {}", tenant.slug, e.message, e)
            }
        }

        logger.info("{JobAdı} tamamlandı (tüm tenant'lar)")
    }
}
```

### 4. Cron ifade örnekleri

| İfade | Açıklama |
|---|---|
| `0 0 * * * ?` | Her saat başı |
| `0 0 4 * * ?` | Her gün saat 04:00 |
| `0 */30 * * * ?` | Her 30 dakikada |
| `0 0 0 * * MON` | Her pazartesi gece yarısı |
| `0 0 9 * * ?` | Her gün saat 09:00 |

### 5. Kontrol listesi

- [ ] `@Component` annotation var
- [ ] `@Scheduled` doğru parametre ile (cron veya fixedRate)
- [ ] `@SchedulerLock` var — `lockAtLeastFor` ve `lockAtMostFor` ayarlanmış
- [ ] `TenantAwareScheduler.executeForAllTenants()` kullanılıyor
- [ ] Tenant timezone hesaplanıyor: `ZoneId.of(settings?.timezone ?: "Europe/Istanbul")`
- [ ] Per-item try-catch var (tek hata tüm job'ı durdurmasın)
- [ ] Logger: başlangıç, tamamlanma ve hata loglanıyor
- [ ] Import'lar doğru
