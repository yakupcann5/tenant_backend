Test oluştur: $ARGUMENTS

## Format

`$ARGUMENTS` formatı: `SınıfAdı tip:unit|integration|api`

Örnekler:
- `CouponService tip:unit`
- `CouponRepository tip:integration`
- `CouponController tip:api`

## Kurallar

### 1. Hedef sınıfı oku

Test edilecek sınıfı bul ve oku. Metot imzaları ve bağımlılıkları anla.

### 2. Tip: `unit` — MockK tabanlı birim test

Dosya: `src/test/kotlin/com/aesthetic/backend/usecase/{SınıfAdı}Test.kt`

```kotlin
package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.{feature}.{Entity}
import com.aesthetic.backend.repository.{Entity}Repository
import com.aesthetic.backend.dto.request.Create{Entity}Request
import com.aesthetic.backend.dto.response.{Entity}Response
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.tenant.TenantContext
import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import java.util.*

@ExtendWith(MockKExtension::class)
class {SınıfAdı}Test {

    @MockK
    lateinit var {entity}Repository: {Entity}Repository
    // Diğer mock'lar (bağımlılıklara göre)

    @InjectMockKs
    lateinit var {entity}Service: {Entity}Service

    private val testTenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        TenantContext.setTenantId(testTenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `findById - mevcut entity döndürür`() {
        // given
        val id = "test-id"
        val entity = create{Entity}()
        every { {entity}Repository.findById(id) } returns Optional.of(entity)

        // when
        val result = {entity}Service.findById(id)

        // then
        assertNotNull(result)
        verify(exactly = 1) { {entity}Repository.findById(id) }
    }

    @Test
    fun `findById - bulunamadığında ResourceNotFoundException fırlatır`() {
        // given
        val id = "non-existent-id"
        every { {entity}Repository.findById(id) } returns Optional.empty()

        // when & then
        assertThrows<ResourceNotFoundException> {
            {entity}Service.findById(id)
        }
    }

    @Test
    fun `create - başarılı oluşturma`() {
        // given
        val request = Create{Entity}Request(/* alanlar */)
        val entity = create{Entity}()
        every { {entity}Repository.save(any()) } returns entity

        // when
        val result = {entity}Service.create(request)

        // then
        assertNotNull(result)
        verify(exactly = 1) { {entity}Repository.save(any()) }
    }

    @Test
    fun `delete - mevcut entity siler`() {
        // given
        val id = "test-id"
        val entity = create{Entity}()
        every { {entity}Repository.findById(id) } returns Optional.of(entity)
        every { {entity}Repository.delete(entity) } just runs

        // when
        {entity}Service.delete(id)

        // then
        verify(exactly = 1) { {entity}Repository.delete(entity) }
    }

    // Helper
    private fun create{Entity}(): {Entity} = {Entity}().apply {
        // Test entity oluştur
    }
}
```

### 3. Tip: `integration` — Testcontainers + gerçek DB

Dosya: `src/test/kotlin/com/aesthetic/backend/repository/{SınıfAdı}Test.kt`

```kotlin
package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.{feature}.{Entity}
import com.aesthetic.backend.tenant.TenantContext
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
class {SınıfAdı}Test {

    companion object {
        @Container
        val mysql = MySQLContainer("mysql:8.0")
            .withCommand("--collation-server=utf8mb4_turkish_ci", "--character-set-server=utf8mb4")

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @Autowired
    lateinit var {entity}Repository: {Entity}Repository

    private val testTenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        TenantContext.setTenantId(testTenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
        {entity}Repository.deleteAll()
    }

    @Test
    fun `kaydet ve bul`() {
        // given
        val entity = {Entity}().apply {
            // Alanları set et
        }

        // when
        val saved = {entity}Repository.save(entity)
        val found = {entity}Repository.findById(saved.id!!)

        // then
        Assertions.assertTrue(found.isPresent)
    }

    @Test
    fun `farklı tenant verisi görünmez`() {
        // given — tenant A
        TenantContext.setTenantId("tenant-a")
        val entityA = {entity}Repository.save({Entity}().apply { /* ... */ })

        // when — tenant B
        TenantContext.setTenantId("tenant-b")
        val found = {entity}Repository.findById(entityA.id!!)

        // then — tenant B, tenant A verisini göremez
        Assertions.assertFalse(found.isPresent)
    }
}
```

### 4. Tip: `api` — WebMvcTest + MockMvc

Dosya: `src/test/kotlin/com/aesthetic/backend/controller/{SınıfAdı}Test.kt`

```kotlin
package com.aesthetic.backend.controller

import com.aesthetic.backend.dto.request.Create{Entity}Request
import com.aesthetic.backend.dto.response.{Entity}Response
import com.aesthetic.backend.dto.response.ApiResponse
import com.aesthetic.backend.usecase.{Entity}Service
import com.fasterxml.jackson.databind.ObjectMapper
import com.aesthetic.backend.tenant.TenantContext
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest({Entity}Controller::class)
class {SınıfAdı}Test {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var {entity}Service: {Entity}Service

    private val testTenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        TenantContext.setTenantId(testTenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    @WithMockUser(authorities = ["TENANT_ADMIN"])
    fun `GET - liste döndürür`() {
        // given
        every { {entity}Service.findAll(any()) } returns /* PagedResponse */

        // when & then
        mockMvc.perform(get("/api/admin/{feature-plural}"))
            .andExpect(status().isOk)
    }

    @Test
    @WithMockUser(authorities = ["TENANT_ADMIN"])
    fun `POST - 201 Created döndürür`() {
        // given
        val request = Create{Entity}Request(/* ... */)
        every { {entity}Service.create(any()) } returns /* Response */

        // when & then
        mockMvc.perform(
            post("/api/admin/{feature-plural}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isCreated)
    }

    @Test
    @WithMockUser(authorities = ["CLIENT"])
    fun `POST - yetkisiz kullanıcı 403 alır`() {
        mockMvc.perform(
            post("/api/admin/{feature-plural}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        ).andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(authorities = ["TENANT_ADMIN"])
    fun `DELETE - 204 No Content döndürür`() {
        // given
        every { {entity}Service.delete(any()) } returns Unit

        // when & then
        mockMvc.perform(delete("/api/admin/{feature-plural}/test-id"))
            .andExpect(status().isNoContent)
    }
}
```

### 5. Concurrency testi (gerektiğinde)

Eşzamanlılık gerektiren senaryolarda (randevu çakışması, stok azaltma vb.) `CountDownLatch` + `Thread` kullan:

```kotlin
@Test
fun `eşzamanlı randevu oluşturma çakışma kontrolü`() {
    val threadCount = 5
    val latch = CountDownLatch(threadCount)
    val errors = Collections.synchronizedList(mutableListOf<Exception>())

    repeat(threadCount) {
        Thread {
            try {
                TenantContext.setTenantId(testTenantId)
                // Aynı slot'a randevu oluşturma denemesi
                service.create(request)
            } catch (e: Exception) {
                errors.add(e)
            } finally {
                TenantContext.clear()
                latch.countDown()
            }
        }.start()
    }

    latch.await(10, TimeUnit.SECONDS)
    // Sadece 1 başarılı olmalı, diğerleri çakışma hatası almalı
    assertEquals(threadCount - 1, errors.size)
}
```

### 6. Kontrol listesi

- [ ] Doğru test tipi seçildi (unit/integration/api)
- [ ] Unit: `@ExtendWith(MockKExtension::class)`, `@MockK`, `@InjectMockKs`
- [ ] Integration: `@SpringBootTest`, `@Testcontainers`, MySQL 8.0 (turkish_ci)
- [ ] API: `@WebMvcTest`, MockMvc, `@MockkBean`, `@WithMockUser`
- [ ] TenantContext: `@BeforeEach` set, `@AfterEach` clear (tüm test tiplerinde)
- [ ] Happy path + error case testleri var
- [ ] Tenant izolasyon testi var (integration'da)
- [ ] Yetkilendirme testi var (api'de)
- [ ] Concurrency testi var (gerekiyorsa: CountDownLatch + Thread)
