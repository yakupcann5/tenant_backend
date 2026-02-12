Entity oluştur: $ARGUMENTS

## Format

`$ARGUMENTS` formatı: `EntityAdı alanAdi:Tip alanAdi:Tip ...`

Örnek: `Coupon code:String discountPercent:BigDecimal expiresAt:Instant isActive:Boolean`

## Kurallar

BACKEND_ARCHITECTURE.md ve CLAUDE.md kurallarına uygun bir JPA Entity Kotlin dosyası oluştur.

### Dosya konumu
`src/main/kotlin/com/aesthetic/backend/domain/{feature}/{EntityAdı}.kt`

Feature klasörü entity adından türetilir (örn: Coupon → coupon, BlogPost → blog).

### Entity yapısı

```kotlin
@Entity
@Table(
    name = "{snake_case_çoğul}",
    uniqueConstraints = [
        // Slug varsa: UniqueConstraint(name = "uk_{tablo}_slug_tenant", columnNames = ["slug", "tenant_id"])
        // Benzersiz alan varsa: UniqueConstraint(name = "uk_{tablo}_{alan}_tenant", columnNames = ["{alan}", "tenant_id"])
    ],
    indexes = [
        Index(name = "idx_{tablo}_tenant", columnList = "tenant_id"),
        // Sık sorgulanan alanlara index ekle
    ]
)
class {EntityAdı} : TenantAwareEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null

    // Alanlar buraya — $ARGUMENTS'tan parse et

    @CreationTimestamp
    val createdAt: Instant? = null

    @UpdateTimestamp
    var updatedAt: Instant? = null
}
```

### Tip eşlemeleri

| Argüman tipi | Kotlin tipi | Annotation |
|---|---|---|
| `String` | `var field: String = ""` | — |
| `String?` | `var field: String? = null` | — |
| `BigDecimal` | `var field: BigDecimal = BigDecimal.ZERO` | `@Column(precision = 10, scale = 2)` |
| `Int` | `var field: Int = 0` | — |
| `Boolean` | `var field: Boolean = false` | — (nullable olmasın) |
| `Instant` | `var field: Instant? = null` | — |
| `LocalDate` | `var field: LocalDate = LocalDate.now()` | — |
| `LocalTime` | `var field: LocalTime? = null` | — |
| `Text` | `var field: String = ""` | `@Column(columnDefinition = "TEXT")` |
| `Json` | `var field: String = "{}"` | `@Column(columnDefinition = "JSON")` |
| `Enum:EnumAdı` | `var field: EnumAdı = EnumAdı.DEFAULT` | `@Enumerated(EnumType.STRING)` |
| `Ref:EntityAdı` | `lateinit var field: EntityAdı` | `@ManyToOne(fetch = FetchType.LAZY, optional = false)` + `@JoinColumn(name = "{snake}_id", nullable = false)` |
| `Ref?:EntityAdı` | `var field: EntityAdı? = null` | `@ManyToOne(fetch = FetchType.LAZY)` + `@JoinColumn(name = "{snake}_id")` |
| `Currency` | `var currency: String = "TRY"` | — |

### Ek kurallar

- **@ElementCollection:** Her zaman `@OrderColumn(name = "sort_order")` ekle
- **Snapshot pattern:** İlişkili entity'den kopyalanan alanlar (örn: `Appointment.clientName`). İlişkili entity değişse bile snapshot korunur. Alan adı: `{ilişki}{Alan}` (örn: `clientName`, `clientPhone`, `staffName`)

### Kontrol listesi

- [ ] `TenantAwareEntity()` extend edildi (Tenant, RefreshToken, AuditLog hariç)
- [ ] `@Table(name = "snake_case_çoğul")` doğru
- [ ] ID: `@GeneratedValue(strategy = GenerationType.UUID) val id: String? = null`
- [ ] `@CreationTimestamp` / `@UpdateTimestamp` eklendi
- [ ] Tarih/zaman alanları `Instant` (UTC) — `LocalDateTime` KULLANMA
- [ ] Fiyat alanları `BigDecimal` + `@Column(precision = 10, scale = 2)`
- [ ] İlişkiler `FetchType.LAZY` — EAGER KULLANMA
- [ ] `@Enumerated(EnumType.STRING)` enum alanlarında
- [ ] Slug varsa `UniqueConstraint` eklendi
- [ ] `@ElementCollection` varsa `@OrderColumn(name = "sort_order")` eklendi
- [ ] Snapshot pattern alanları doğru (ilişkili entity'den kopyalanan alanlar)
- [ ] Import'lar doğru (`jakarta.persistence.*`, `java.time.Instant`, `java.math.BigDecimal`, `org.hibernate.annotations.*`)
