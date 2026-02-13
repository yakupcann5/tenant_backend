# Aesthetic SaaS Frontend — Proje Kuralları

Bu dosya, frontend geliştirme sürecinde uyulması gereken kuralları tanımlar. Her kural **neden** o şekilde olduğunu açıklar. Mimari detaylar için `FRONTEND_ARCHITECTURE.md` referans alınır. Backend kuralları için `CLAUDE.md` referans alınır.

---

## 1. Proje Kimliği

- **Tech Stack:** Next.js 15 (App Router) + React 19 + TypeScript (strict mode)
- **Styling:** Tailwind CSS v4 + shadcn/ui (Radix primitives)
- **Data Fetching:** TanStack Query v5
- **Data Table:** TanStack Table (headless)
- **Forms:** React Hook Form + Zod
- **State Management:** Zustand (client state) + TanStack Query (server state) + URL searchParams (URL state)
- **Auth/Token:** HttpOnly Cookie (JWT from Spring Boot backend)
- **Date/Time:** date-fns + date-fns-tz
- **Rich Text Editor:** Tiptap
- **Charts:** Recharts
- **Calendar:** FullCalendar
- **Animations:** Framer Motion
- **Toast/Notifications:** Sonner
- **Image Upload:** react-dropzone + react-image-crop
- **Icons:** Lucide React
- **i18n:** next-intl
- **Dark Mode:** next-themes
- **Error Tracking:** Sentry (@sentry/nextjs)
- **Package Manager:** pnpm
- **Code Quality:** ESLint + Prettier + Husky + lint-staged
- **Mimari:** Multi-Tenant SaaS — Subdomain bazlı tenant çözümleme
- **Mimari referans:** `FRONTEND_ARCHITECTURE.md`
- **Backend referans:** `BACKEND_ARCHITECTURE.md`, `CLAUDE.md`

---

## 2. Dosya/Klasör Kuralları

### 2.1 Naming Convention

| Tür | Convention | Örnek |
|-----|-----------|-------|
| Dosya (component) | kebab-case | `appointment-card.tsx`, `booking-wizard.tsx` |
| Dosya (hook) | kebab-case, `use-` prefix | `use-tenant.ts`, `use-terminology.ts` |
| Dosya (utility) | kebab-case | `api-client.ts`, `date-utils.ts` |
| Dosya (type) | kebab-case | `appointment.types.ts`, `auth.types.ts` |
| Component export | PascalCase | `export function AppointmentCard()` |
| Hook export | camelCase, `use` prefix | `export function useTenant()` |
| Constant | UPPER_SNAKE_CASE | `export const API_BASE_URL = ...` |
| Enum/const object | PascalCase key | `AppointmentStatus.PENDING` |
| Query key factory | camelCase | `appointmentKeys.list(filters)` |
| Zustand store | camelCase, `use` prefix | `export const useSidebarStore = create(...)` |

*WHY: kebab-case dosya adları case-insensitive dosya sistemlerinde (macOS, Windows) çakışma önler. PascalCase component export'ları JSX'te HTML element'lerinden ayırt edilir. Tutarlı convention arama ve navigation'ı hızlandırır.*

### 2.2 Klasör Yapısı Kuralları

```
src/
├── app/                     → Next.js App Router (route tanımları)
│   ├── (public)/            → Herkese açık sayfalar
│   ├── (auth)/              → Login, register, forgot-password
│   ├── (admin)/             → Tenant admin paneli
│   ├── (staff)/             → Personel paneli
│   └── (client)/            → Müşteri paneli
├── features/                → Feature-first modüller
│   ├── appointments/        → Randevu feature
│   │   ├── api/             → Query/mutation hook'ları
│   │   ├── components/      → Feature-specific component'ler
│   │   ├── hooks/           → Feature-specific hook'lar
│   │   ├── types/           → Feature-specific type'lar
│   │   └── utils/           → Feature-specific utility'ler
│   ├── auth/
│   ├── services/
│   └── ...
├── components/              → Paylaşılan component'ler
│   ├── ui/                  → shadcn/ui base component'ler
│   ├── layout/              → Layout component'ler (sidebar, header)
│   └── guards/              → Auth, module, role guard'lar
├── lib/                     → Core utility'ler
│   ├── api-client.ts        → Axios instance + interceptor'lar
│   ├── query-client.ts      → TanStack Query client config
│   └── utils.ts             → cn(), formatDate() gibi helper'lar
├── hooks/                   → Global hook'lar
├── stores/                  → Zustand store'lar
├── types/                   → Global type tanımları
├── messages/                → next-intl çeviri dosyaları
└── styles/                  → Global CSS
```

*WHY: Feature-first organizasyon, ilgili tüm dosyaları (API, component, hook, type) bir arada tutar. Bir feature'ı silmek = bir klasörü silmek. `components/` sadece feature'lar arası paylaşılan component'ler içerir.*

### 2.3 Barrel Export Kuralları

```typescript
// features/appointments/index.ts — Feature barrel export
export { AppointmentCard } from './components/appointment-card';
export { BookingWizard } from './components/booking-wizard';
export { useAppointments, useCreateAppointment } from './api/queries';
export type { AppointmentResponse, CreateAppointmentRequest } from './types';
```

**Kurallar:**
- Her feature klasörü bir `index.ts` barrel export dosyası içermeli — *WHY: Dışarıdan `import { X } from '@/features/appointments'` şeklinde temiz import sağlar*
- `components/ui/` klasöründe barrel export YAPMA — *WHY: shadcn/ui component'leri zaten tek dosya, barrel export tree-shaking'i engeller*
- Circular dependency oluşturan barrel export'tan KAÇIN — *WHY: Webpack/Turbopack sonsuz döngüye girer, build patlar*

### 2.4 Import Sıralaması

```typescript
// 1. React ve Next.js
import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import Image from 'next/image';

// 2. Üçüncü parti kütüphaneler
import { useQuery } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

// 3. Proje alias'ları (@/)
import { Button } from '@/components/ui/button';
import { useTenant } from '@/hooks/use-tenant';
import { apiClient } from '@/lib/api-client';

// 4. Relative import'lar
import { AppointmentCard } from './appointment-card';
import type { AppointmentResponse } from '../types';

// 5. Type-only import'lar
import type { FC, ReactNode } from 'react';
```

*WHY: Tutarlı import sıralaması code review'u hızlandırır ve merge conflict'leri azaltır. ESLint `import/order` kuralı ile enforce edilir.*

---

## 3. Component Kuralları

### 3.1 Server Component vs Client Component

```
Server Component (varsayılan):
├── Veri fetch'leme (async/await)
├── Backend'e doğrudan erişim
├── SEO-kritik içerik (metadata, JSON-LD)
├── Statik layout'lar
└── Büyük dependency'leri client bundle'dan uzak tutma

Client Component ("use client"):
├── Event handler'lar (onClick, onChange)
├── State (useState, useReducer)
├── Effect'ler (useEffect)
├── Browser API'leri (localStorage, window)
├── TanStack Query hook'ları
└── Interaktif UI (form, modal, dropdown)
```

**Kurallar:**

1. **"use client" sadece gerektiğinde ekle** — *WHY: Server Component default'tur, gereksiz "use client" server-side rendering avantajını kaybettirir ve bundle size artırır*

2. **"use client" boundary'yi mümkün olduğunca aşağı it** — *WHY: Tüm sayfa yerine sadece interaktif kısmı Client Component yap. Bu sayede sayfa layout'u server'da render edilir, sadece interaktif widget client bundle'a girer.*

```typescript
// YANLIŞ: Tüm sayfa "use client"
// ❌ app/(admin)/appointments/page.tsx
"use client";
export default function AppointmentsPage() {
  const { data } = useAppointments(); // Bu tek hook için tüm sayfa client oldu
  return <div>...</div>;
}

// DOĞRU: Sayfa Server Component, interaktif kısım ayrı Client Component
// ✅ app/(admin)/appointments/page.tsx (Server Component)
export default function AppointmentsPage() {
  return (
    <div>
      <h1>Randevular</h1>
      <AppointmentList /> {/* Bu Client Component */}
    </div>
  );
}

// ✅ features/appointments/components/appointment-list.tsx
"use client";
export function AppointmentList() {
  const { data } = useAppointments();
  return <DataTable data={data} />;
}
```

3. **Server Component'te hook KULLANAMAZSIN** — *WHY: useState, useEffect, useQuery gibi hook'lar sadece Client Component'te çalışır. Server Component'te kullanmak build hatası verir.*

4. **Client Component'e Server Component children olarak geçilebilir** — *WHY: Composition pattern ile client boundary'yi daraltabilirsin.*

```typescript
// ✅ Client Component, Server Component children alır
"use client";
export function InteractiveWrapper({ children }: { children: ReactNode }) {
  const [isOpen, setIsOpen] = useState(false);
  return (
    <div>
      <button onClick={() => setIsOpen(!isOpen)}>Toggle</button>
      {isOpen && children} {/* children Server Component olabilir */}
    </div>
  );
}
```

### 3.2 Component Tanımlama

```typescript
// DOĞRU: Named function export
export function AppointmentCard({ appointment }: AppointmentCardProps) {
  return <div>...</div>;
}

// YANLIŞ: Default export (refactoring'de isim kaybı)
// export default function AppointmentCard() { ... }

// YANLIŞ: Arrow function component (displayName kaybı)
// export const AppointmentCard = ({ appointment }: Props) => { ... }
```

*WHY: Named function export'lar React DevTools'ta ve error stack trace'lerde doğru isimle görünür. Default export'lar import sırasında herhangi bir isimle import edilebilir — tutarsızlık yaratır. Arrow function'lar displayName'i otomatik almaz.*

**İstisnalar:** Higher-order pattern'lerde arrow function kabul edilir (forwardRef, memo wrapper).

### 3.3 Props Tanımlama

```typescript
// DOĞRU: Interface ile props tanımla
interface AppointmentCardProps {
  appointment: AppointmentResponse;
  onStatusChange?: (status: AppointmentStatus) => void;
  className?: string;
}

export function AppointmentCard({ appointment, onStatusChange, className }: AppointmentCardProps) {
  // ...
}
```

**Kurallar:**
- Props her zaman interface ile tanımla (type alias değil) — *WHY: Interface extend edilebilir, declaration merging destekler*
- `{Entity}Props` naming convention — *WHY: Component adıyla eşleşen props aranabilirliği artırır*
- `className` prop'u her zaman opsiyonel kabul et — *WHY: Dışarıdan stil override'ı mümkün olur*
- children gerekiyorsa `ReactNode` tip kullan — *WHY: string, number, element, fragment, null hepsini kabul eder*
- Callback prop'lar `on` prefix'i ile — `onSubmit`, `onCancel`, `onStatusChange` — *WHY: React convention, event handler olduğunu belirtir*

### 3.4 Composition over Prop Drilling

```typescript
// YANLIŞ: 5+ seviye prop drilling
// <Page tenant={t} /> → <Layout tenant={t} /> → <Sidebar tenant={t} /> → <Logo tenant={t} />

// DOĞRU: Context veya Zustand ile paylaş
// Tenant bilgisi → TenantProvider context
// UI state (sidebar açık/kapalı) → Zustand store
// Server state (randevu listesi) → TanStack Query
```

**Ne Nerede Tutulur:**

| Veri Tipi | Çözüm | Kural |
|-----------|-------|-------|
| Tenant bilgisi | TenantContext (React Context) | Provider → useTenant() hook |
| Auth state | AuthContext (React Context) | Provider → useAuth() hook |
| Tema bilgisi | ThemeProvider (next-themes) | Provider → useTheme() hook |
| UI state (sidebar, modal) | Zustand store | Küçük, odaklı store'lar |
| Server state (entity listesi) | TanStack Query | Query key + hook |
| Filtre/sıralama/sayfa | URL searchParams | useSearchParams() + nuqs |
| Form state | React Hook Form | useForm() — Zustand'a TAŞIMA |
| Geçici UI state (hover, focus) | useState | Component-local |

*WHY: Prop drilling 3+ seviyeyi geçtiğinde bakım maliyeti katlanır. Her değişiklikte tüm ara component'ler güncellenmeli. Context/Zustand bu bağımlılık zincirini kırar.*

### 3.5 Conditional Rendering Kuralları

```typescript
// DOĞRU: Early return ile guard
export function AppointmentDetail({ id }: { id: string }) {
  const { data, isLoading, error } = useAppointment(id);

  if (isLoading) return <AppointmentDetailSkeleton />;
  if (error) return <ErrorMessage error={error} />;
  if (!data) return <NotFound message="Randevu bulunamadı" />;

  return <div>{/* Ana içerik */}</div>;
}

// YANLIŞ: Nested ternary
// return isLoading ? <Skeleton /> : error ? <Error /> : data ? <Content /> : <NotFound />;
```

*WHY: Early return okunabilirliği artırır, her durum açıkça handle edilir. Nested ternary 3+ seviyede okunamaz hale gelir.*

---

## 4. TypeScript Kuralları

### 4.1 Strict Mode Zorunlu

```json
// tsconfig.json
{
  "compilerOptions": {
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "exactOptionalPropertyTypes": false
  }
}
```

*WHY: `strict: true` null/undefined hatalarını derleme zamanında yakalar. `noUncheckedIndexedAccess` array/object index erişiminde `T | undefined` döndürür — runtime `Cannot read property of undefined` hatalarını önler.*

### 4.2 Interface vs Type

```typescript
// DOĞRU: Object shape → interface
interface AppointmentResponse {
  id: string;
  date: string;
  status: AppointmentStatus;
  clientName: string;
}

// DOĞRU: Union, intersection, mapped type → type alias
type AppointmentStatusFilter = AppointmentStatus | 'ALL';
type FormMode = 'create' | 'edit';
type WithTenantId<T> = T & { tenantId: string };

// DOĞRU: Function type → type alias
type OnStatusChange = (id: string, status: AppointmentStatus) => void;
```

*WHY: Interface'ler extend edilebilir, declaration merging destekler, error mesajları daha okunabilir. Type alias'lar union, intersection, conditional type gibi gelişmiş tipleri ifade eder. Karıştırmak tutarsızlık yaratır.*

### 4.3 Enum vs Const Object

```typescript
// DOĞRU: Backend enum'ları → const object + type (tree-shakeable)
export const AppointmentStatus = {
  PENDING: 'PENDING',
  CONFIRMED: 'CONFIRMED',
  IN_PROGRESS: 'IN_PROGRESS',
  COMPLETED: 'COMPLETED',
  CANCELLED: 'CANCELLED',
  NO_SHOW: 'NO_SHOW',
} as const;

export type AppointmentStatus = (typeof AppointmentStatus)[keyof typeof AppointmentStatus];

// Backend'deki enum ile birebir eşleşmeli:
// Kotlin: enum class AppointmentStatus { PENDING, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW }
```

*WHY: TypeScript `enum` runtime'da object üretir, tree-shaking'i engeller, bundle size artırır. `as const` + type extraction aynı tip güvenliğini sağlar, sıfır runtime maliyetle. Backend enum'larıyla string değerleri birebir eşleşir.*

**Tüm backend enum'ları bu pattern ile tanımlanmalı:**
- `AppointmentStatus` — 6 değer
- `BusinessType` — 9 değer (HAIR_SALON, BEAUTY_CENTER, NAIL_SALON, SPA_WELLNESS, BARBERSHOP, DENTAL_CLINIC, AESTHETIC_CLINIC, VETERINARY_CLINIC, TATTOO_STUDIO)
- `FeatureModule` — 9 değer (APPOINTMENTS, PRODUCTS, BLOG, GALLERY, REVIEWS, CONTACT_MESSAGES, PATIENT_RECORDS, NOTIFICATIONS, CLIENT_NOTES)
- `ErrorCode` — 11 değer
- `Role` — 4 değer (TENANT_ADMIN, STAFF, CLIENT, PLATFORM_ADMIN)
- `SubscriptionPlan` — 5 değer (TRIAL, STARTER, PROFESSIONAL, BUSINESS, ENTERPRISE)
- `SubscriptionStatus` — 5 değer (TRIAL, ACTIVE, PAST_DUE, EXPIRED, CANCELLED)
- `NotificationType` — 11 değer
- `DeliveryStatus` — 4 değer (PENDING, SENT, FAILED, BOUNCED)

### 4.4 Backend DTO Type Mapping

```typescript
// Backend DTO → Frontend Interface (birebir eşleşme)

// Backend: data class CreateAppointmentRequest(
//   @field:NotBlank val serviceIds: List<String>,
//   @field:NotBlank val date: String,
//   @field:NotBlank val startTime: String,
//   val staffId: String?,
//   val clientName: String?,
//   val clientEmail: String?,
//   val clientPhone: String?,
//   val notes: String?
// )
interface CreateAppointmentRequest {
  serviceIds: string[];
  date: string;          // "2025-03-15" (ISO LocalDate)
  startTime: string;     // "10:00" (ISO LocalTime)
  staffId?: string;
  clientName?: string;
  clientEmail?: string;
  clientPhone?: string;
  notes?: string;
}

// Backend: data class AppointmentResponse(...)
interface AppointmentResponse {
  id: string;
  date: string;
  startTime: string;
  endTime: string;
  status: AppointmentStatus;
  clientName: string;
  clientEmail: string;
  clientPhone: string;
  staffId: string;
  staffName: string;
  services: AppointmentServiceResponse[];
  totalPrice: number;
  totalDuration: number;
  notes?: string;
  cancellationReason?: string;
  cancelledAt?: string;
  createdAt: string;
  updatedAt: string;
}
```

**Kurallar:**
- Backend `BigDecimal` → Frontend `number` — *WHY: JSON serialization'da number olarak gelir*
- Backend `Instant` → Frontend `string` (ISO 8601) — *WHY: JSON'da string olarak serialize edilir, `date-fns` ile parse edilir*
- Backend `LocalDate` → Frontend `string` ("2025-03-15") — *WHY: Date objesi timezone sorunları yaratır*
- Backend `LocalTime` → Frontend `string` ("10:00") — *WHY: Sade string karşılaştırma yeterli*
- Backend `nullable` alan → Frontend `optional` (`?`) — *WHY: Kotlin nullable = TypeScript optional*
- Backend `List<T>` → Frontend `T[]` — *WHY: Array syntax daha okunabilir*
- Response type'ta **OLMAYACAKLAR**: `tenantId`, `passwordHash`, `version`, `failedLoginAttempts`, `lockedUntil` — *WHY: Backend zaten bunları response'a dahil etmez, güvenlik*

### 4.5 API Response Wrapper Type'ları

```typescript
// Backend ApiResponse<T> karşılığı
interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
  timestamp: string;
}

// Backend PagedResponse<T> karşılığı
interface PagedResponse<T> {
  success: boolean;
  data: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  timestamp: string;
}

// Backend ErrorResponse karşılığı
interface ErrorResponse {
  success: boolean;
  error: string;
  code: ErrorCode;
  details?: Record<string, string>;
  timestamp: string;
}
```

*WHY: Backend'in üç response wrapper'ı frontend'de birebir karşılığa sahip olmalı. Generic `ApiResponse<T>` ile tip güvenli response unwrapping sağlanır.*

### 4.6 Null Handling

```typescript
// DOĞRU: Optional chaining + nullish coalescing
const clientName = appointment?.clientName ?? 'Bilinmeyen';
const phone = user?.phone ?? '';

// DOĞRU: Type narrowing ile guard
function renderPrice(price: number | undefined) {
  if (price === undefined) return <span>—</span>;
  return <span>{formatCurrency(price)}</span>;
}

// YANLIŞ: Non-null assertion (!)
// const name = appointment!.clientName;  // ← Runtime hatasını gizler

// YANLIŞ: Truthy check ile 0 ve "" kaybı
// if (appointment.totalPrice) { ... }  // ← 0 TL fiyat false olarak değerlendirilir
```

*WHY: Non-null assertion (`!`) TypeScript'in null güvenlik garantisini bypass eder. Truthy check `0`, `""`, `false` gibi geçerli falsy değerleri yanlış filtreler.*

### 4.7 Generic Kullanımı

```typescript
// DOĞRU: API hook'larında generic
function useApiQuery<T>(
  queryKey: QueryKey,
  url: string,
  options?: UseQueryOptions<ApiResponse<T>, AxiosError<ErrorResponse>>
) {
  return useQuery({
    queryKey,
    queryFn: () => apiClient.get<ApiResponse<T>>(url).then(res => res.data),
    ...options,
  });
}

// DOĞRU: DataTable'da generic
interface DataTableProps<T> {
  data: T[];
  columns: ColumnDef<T>[];
  onRowClick?: (row: T) => void;
}

function DataTable<T>({ data, columns, onRowClick }: DataTableProps<T>) { ... }
```

*WHY: Generic'ler tip güvenliğini koruyarak yeniden kullanılabilir component/hook oluşturmayı sağlar. `any` kullanımını ortadan kaldırır.*

---

## 5. Data Fetching Kuralları

### 5.1 Query Key Convention

```typescript
// DOĞRU: Factory pattern ile query key
export const appointmentKeys = {
  all: ['appointments'] as const,
  lists: () => [...appointmentKeys.all, 'list'] as const,
  list: (filters: AppointmentFilters) => [...appointmentKeys.lists(), filters] as const,
  details: () => [...appointmentKeys.all, 'detail'] as const,
  detail: (id: string) => [...appointmentKeys.details(), id] as const,
} as const;

// Kullanım:
useQuery({ queryKey: appointmentKeys.list({ status: 'PENDING', page: 0 }), ... });
useQuery({ queryKey: appointmentKeys.detail(appointmentId), ... });

// Cache invalidation:
queryClient.invalidateQueries({ queryKey: appointmentKeys.lists() }); // Tüm listeler
queryClient.invalidateQueries({ queryKey: appointmentKeys.all });     // Her şey
```

*WHY: Factory pattern query key'leri merkezi yönetir. Invalidation'da `appointmentKeys.lists()` ile tüm filtre kombinasyonlarını tek seferde invalidate edebilirsin. String literal key'ler refactoring'de kırılır ve typo riski taşır.*

### 5.2 Query Hook Pattern

```typescript
// features/appointments/api/queries.ts

export function useAppointments(filters: AppointmentFilters) {
  return useQuery({
    queryKey: appointmentKeys.list(filters),
    queryFn: () => appointmentApi.getList(filters),
    staleTime: 30 * 1000,        // 30 saniye
    placeholderData: keepPreviousData, // Sayfa geçişlerinde önceki veriyi göster
  });
}

export function useAppointment(id: string) {
  return useQuery({
    queryKey: appointmentKeys.detail(id),
    queryFn: () => appointmentApi.getById(id),
    enabled: !!id, // id yoksa query çalışmasın
  });
}
```

**Kurallar:**
- Her feature'ın `api/` klasöründe query hook'ları olmalı — *WHY: Data fetching logic component'ten ayrılır, yeniden kullanılabilir*
- `enabled` ile conditional query — *WHY: Gereksiz API call'ları önler (id olmadan detail fetch etme)*
- `staleTime` her query'ye açıkça belirt — *WHY: Default 0'dır, her focus'ta refetch yapar — gereksiz API yükü*
- `placeholderData: keepPreviousData` pagination'da kullan — *WHY: Sayfa geçişlerinde loading flash önler*

### 5.3 Stale Time Rehberi

| Veri Tipi | staleTime | Gerekçe |
|-----------|-----------|---------|
| SiteSettings (tema, timezone) | 5 dk | Nadir değişir |
| Hizmetler / Ürünler | 2 dk | Orta volatilite |
| Randevu listesi | 30 sn | Sık güncellenir |
| Dashboard istatistikleri | 1 dk | Sık ama real-time gerekmez |
| Blog / Galeri | 5 dk | İçerik nadiren güncellenir |
| Bildirimler (unread count) | 15 sn | Kullanıcı hızlı görmeli |
| Müsaitlik slotları | **0 (cache YOK)** | Gerçek zamanlı doğruluk şart |

*WHY: Her veri tipi farklı değişim hızına sahiptir. SiteSettings dakikada 1 kez değişirken, müsaitlik slotları her saniye değişebilir. Tek bir staleTime ya stale data ya gereksiz refetch üretir.*

### 5.4 Mutation Pattern

```typescript
export function useCreateAppointment() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (data: CreateAppointmentRequest) => appointmentApi.create(data),
    onSuccess: () => {
      // İlgili query'leri invalidate et
      queryClient.invalidateQueries({ queryKey: appointmentKeys.lists() });
      queryClient.invalidateQueries({ queryKey: dashboardKeys.all });
      toast.success('Randevu başarıyla oluşturuldu');
    },
    onError: (error: AxiosError<ErrorResponse>) => {
      const errorCode = error.response?.data?.code;
      if (errorCode === ErrorCode.APPOINTMENT_CONFLICT) {
        toast.error('Bu zaman diliminde çakışma var. Lütfen başka bir saat seçin.');
      } else if (errorCode === ErrorCode.PLAN_LIMIT_EXCEEDED) {
        toast.error('Aylık randevu limitinize ulaştınız. Planınızı yükseltin.');
      } else {
        toast.error(getErrorMessage(error));
      }
    },
  });
}
```

**Kurallar:**
- Mutation sonrası ilgili TÜM query'leri invalidate et — *WHY: Randevu oluşturulunca hem liste hem dashboard güncellenmeli*
- `onError`'da `ErrorCode` bazlı mesaj göster — *WHY: Backend'in error code'u kullanıcı dostu mesaja çevrilmeli*
- Mutation hook'ları da feature `api/` klasöründe tanımla — *WHY: Fetching logic merkezi kalır*
- Toast mesajını mutation hook'unda göster, component'te DEĞİL — *WHY: Aynı mutation farklı component'lerde kullanıldığında toast tekrarı önlenir*

### 5.5 Optimistic Update Kuralları

```typescript
// SADECE basit, geri alınabilir işlemler için optimistic update kullan
export function useUpdateAppointmentStatus() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: AppointmentStatus }) =>
      appointmentApi.updateStatus(id, status),
    onMutate: async ({ id, status }) => {
      // 1. Devam eden query'leri iptal et
      await queryClient.cancelQueries({ queryKey: appointmentKeys.detail(id) });

      // 2. Mevcut veriyi snapshot al
      const previous = queryClient.getQueryData(appointmentKeys.detail(id));

      // 3. Optimistic update
      queryClient.setQueryData(appointmentKeys.detail(id), (old: any) => ({
        ...old,
        data: { ...old.data, status },
      }));

      return { previous };
    },
    onError: (_err, { id }, context) => {
      // 4. Hata durumunda geri al
      if (context?.previous) {
        queryClient.setQueryData(appointmentKeys.detail(id), context.previous);
      }
      toast.error('Durum güncellenirken bir hata oluştu');
    },
    onSettled: (_data, _err, { id }) => {
      // 5. Her durumda yeniden fetch et (source of truth: server)
      queryClient.invalidateQueries({ queryKey: appointmentKeys.detail(id) });
      queryClient.invalidateQueries({ queryKey: appointmentKeys.lists() });
    },
  });
}
```

**Ne Zaman Optimistic Update KULLANILMALI:**
- ✅ Status toggle (aktif/pasif)
- ✅ Favori ekleme/çıkarma
- ✅ Bildirim okundu işaretleme

**Ne Zaman Optimistic Update KULLANILMAMALI:**
- ❌ Randevu oluşturma (conflict kontrolü server'da) — *WHY: Çakışma varsa geri almak karmaşık UX yaratır*
- ❌ Ödeme işlemleri — *WHY: Para ile ilgili işlemlerde false positive tehlikeli*
- ❌ Silme işlemleri — *WHY: Geri alma karmaşık, kullanıcı kafası karışır*

### 5.6 Cache Invalidation Tablosu

| Aksiyon | Invalidate Edilecek Query Key'ler |
|---------|----------------------------------|
| Randevu oluştur | `appointmentKeys.lists()`, `dashboardKeys.all`, `availabilityKeys.all` |
| Randevu iptal | `appointmentKeys.lists()`, `appointmentKeys.detail(id)`, `dashboardKeys.all` |
| Randevu status güncelle | `appointmentKeys.detail(id)`, `appointmentKeys.lists()`, `dashboardKeys.all` |
| Hizmet oluştur/güncelle | `serviceKeys.lists()`, `serviceKeys.detail(id)` |
| Personel oluştur/güncelle | `staffKeys.lists()`, `staffKeys.detail(id)`, `availabilityKeys.all` |
| Site ayarları güncelle | `settingsKeys.all` |
| Profil güncelle | `authKeys.me` |

*WHY: Eksik invalidation stale data gösterir. Fazla invalidation gereksiz refetch yapar. Bu tablo her mutation için hangi query'lerin etkilendiğini tanımlar.*

### 5.7 Infinite Scroll / Pagination

```typescript
// Infinite scroll — blog, galeri gibi public sayfalarda
export function useInfiniteBlogs(filters: BlogFilters) {
  return useInfiniteQuery({
    queryKey: blogKeys.list(filters),
    queryFn: ({ pageParam = 0 }) => blogApi.getList({ ...filters, page: pageParam }),
    getNextPageParam: (lastPage) =>
      lastPage.page < lastPage.totalPages - 1 ? lastPage.page + 1 : undefined,
    initialPageParam: 0,
  });
}

// Klasik pagination — admin panel tablolarında
export function useAppointments(filters: AppointmentFilters) {
  return useQuery({
    queryKey: appointmentKeys.list(filters), // filters içinde page var
    queryFn: () => appointmentApi.getList(filters),
    placeholderData: keepPreviousData,
  });
}
```

*WHY: Admin panelde DataTable pagination (sayfa numarası) daha uygun — toplam kayıt görünümü gerekli. Public sayfalarda infinite scroll daha iyi UX sağlar — scroll ile yeni içerik yükle.*

---

## 6. Form Kuralları

### 6.1 Zod Schema Convention

```typescript
// DOĞRU: Schema → type extract (DRY)
export const createAppointmentSchema = z.object({
  serviceIds: z.array(z.string()).min(1, 'En az bir hizmet seçiniz'),
  date: z.string().min(1, 'Tarih seçiniz'),
  startTime: z.string().min(1, 'Saat seçiniz'),
  staffId: z.string().optional(),
  clientName: z.string().optional(),
  clientEmail: z.string().email('Geçerli bir e-posta adresi giriniz').optional().or(z.literal('')),
  clientPhone: z.string().optional(),
  notes: z.string().max(500, 'Notlar en fazla 500 karakter olabilir').optional(),
});

// Type'ı schema'dan çıkar — ayrı interface tanımlama
export type CreateAppointmentFormData = z.infer<typeof createAppointmentSchema>;
```

*WHY: Zod schema hem runtime validation hem TypeScript type üretir. Ayrı interface tanımlamak DRY ihlali — schema ve interface senkronizasyonu bozulur.*

**Schema dosya konumu:** `features/{feature}/types/{feature}.schemas.ts` — *WHY: Feature'a özgü validation logic feature klasöründe kalır*

### 6.2 Form Hook Pattern

```typescript
"use client";

import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';

export function AppointmentForm({ onSuccess }: { onSuccess: () => void }) {
  const form = useForm<CreateAppointmentFormData>({
    resolver: zodResolver(createAppointmentSchema),
    defaultValues: {
      serviceIds: [],
      date: '',
      startTime: '',
      staffId: undefined,
      notes: '',
    },
  });

  const createMutation = useCreateAppointment();

  const onSubmit = form.handleSubmit((data) => {
    createMutation.mutate(data, {
      onSuccess: () => {
        form.reset();
        onSuccess();
      },
    });
  });

  return (
    <Form {...form}>
      <form onSubmit={onSubmit}>
        {/* Form alanları */}
        <Button type="submit" disabled={createMutation.isPending}>
          {createMutation.isPending ? 'Kaydediliyor...' : 'Kaydet'}
        </Button>
      </form>
    </Form>
  );
}
```

**Kurallar:**
- `zodResolver` her zaman kullan — *WHY: Client-side validation backend'e gereksiz request gönderimini önler*
- `defaultValues` her zaman tanımla — *WHY: Uncontrolled → controlled geçiş uyarısını önler, form reset'te tutarlı davranış*
- Submit button'da `disabled={mutation.isPending}` — *WHY: Double submit önleme*
- `form.reset()` başarılı submit'te — *WHY: Form state temizlenmezse stale veri kalır*
- Form state'i Zustand'a TAŞIMA — *WHY: React Hook Form kendi state yönetimini yapar, Zustand ile senkronizasyon gereksiz karmaşıklık*

### 6.3 Validation Mesaj Dili

```typescript
// Validation mesajları Türkçe, next-intl ile lokalize
const schema = z.object({
  email: z.string()
    .min(1, 'Bu alan zorunludur')
    .email('Geçerli bir e-posta adresi giriniz'),
  name: z.string()
    .min(1, 'Bu alan zorunludur')
    .min(2, 'En az 2 karakter olmalıdır')
    .max(100, 'En fazla 100 karakter olabilir'),
  phone: z.string()
    .regex(/^(\+90|0)?[0-9]{10}$/, 'Geçerli bir telefon numarası giriniz')
    .optional()
    .or(z.literal('')),
  price: z.number()
    .min(0, 'Fiyat negatif olamaz')
    .max(999999.99, 'Fiyat çok yüksek'),
});
```

*WHY: Validation mesajları kullanıcının diliyle gösterilmeli. Backend `@field:NotBlank(message = "...")` mesajlarıyla tutarlı olmalı.*

### 6.4 Edit Form Pre-fill Pattern

```typescript
export function EditServiceForm({ serviceId }: { serviceId: string }) {
  const { data: service } = useService(serviceId);

  const form = useForm<UpdateServiceFormData>({
    resolver: zodResolver(updateServiceSchema),
    // defaultValues service yüklendiğinde set edilir
    values: service ? {
      name: service.name,
      price: service.price,
      duration: service.duration,
      description: service.description ?? '',
    } : undefined,
  });

  // ...
}
```

*WHY: `values` prop'u React Hook Form'a dışarıdan gelen veriyi otomatik senkronize eder. `defaultValues` sadece ilk render'da çalışır — async veri geldiğinde form boş kalır. `values` ile veri geldiğinde form otomatik dolar.*

### 6.5 Server Error → Form Error Mapping

```typescript
const onSubmit = form.handleSubmit((data) => {
  createMutation.mutate(data, {
    onError: (error: AxiosError<ErrorResponse>) => {
      const details = error.response?.data?.details;
      if (details) {
        // Backend validation hataları → form alanlarına eşle
        Object.entries(details).forEach(([field, message]) => {
          form.setError(field as keyof CreateServiceFormData, {
            type: 'server',
            message,
          });
        });
      }
    },
  });
});
```

*WHY: Backend `VALIDATION_ERROR` response'unda `details` map'i alan adı → hata mesajı içerir. Bu detayları form alanlarının altında göstermek backend-frontend validation tutarlılığı sağlar.*

---

## 7. Stil Kuralları

### 7.1 Tailwind Class Convention

```typescript
// DOĞRU: cn() utility ile conditional class
import { cn } from '@/lib/utils';

export function StatusBadge({ status, className }: StatusBadgeProps) {
  return (
    <span className={cn(
      'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium',
      statusStyles[status],
      className // Dışarıdan override mümkün
    )}>
      {statusLabels[status]}
    </span>
  );
}

// YANLIŞ: String concatenation
// className={`px-2 py-1 ${status === 'PENDING' ? 'bg-yellow-100' : 'bg-green-100'} ${className}`}
```

*WHY: `cn()` (clsx + tailwind-merge) çakışan class'ları akıllıca birleştirir. String concatenation'da `px-2` ve `px-4` yan yana kalır — hangisinin geçerli olduğu belirsiz.*

### 7.2 Class Sıralama

```
Layout → Spacing → Size → Typography → Colors → Border → Effects → Responsive → State
```

```typescript
// Örnek:
className="flex items-center gap-3 p-4 w-full text-sm text-gray-700 bg-white border rounded-lg shadow-sm hover:bg-gray-50 transition-colors md:p-6 lg:gap-4"
// Layout:   flex items-center
// Spacing:  gap-3 p-4
// Size:     w-full
// Typography: text-sm
// Colors:   text-gray-700 bg-white
// Border:   border rounded-lg
// Effects:  shadow-sm
// State:    hover:bg-gray-50 transition-colors
// Responsive: md:p-6 lg:gap-4
```

*WHY: Tutarlı sıralama class listelerini taranabilir yapar. Prettier plugin `prettier-plugin-tailwindcss` bu sıralamayı otomatik enforce eder.*

### 7.3 Tema Renkleri — Hardcode YAPMA

```typescript
// DOĞRU: CSS custom property (tema uyumlu)
className="bg-primary text-primary-foreground"
className="border-border bg-card text-card-foreground"

// YANLIŞ: Hardcode renk
// className="bg-blue-600 text-white"  // ← Tema değişince bozulur
// className="bg-[#2563eb]"            // ← Arbitrary value, tema desteği yok
```

*WHY: Tema sistemi CSS custom property'ler üzerinden çalışır. Hardcode renk kullanmak hem dark mode'u hem tenant tema override'ını kırar. Sadece `primary`, `secondary`, `accent`, `muted`, `destructive` gibi semantik renk token'ları kullan.*

**İstisna:** One-off renk gerektiren durumlar (status badge'leri gibi) Tailwind renkleri kullanabilir — ama bunlar da bir map üzerinden yönetilmeli:

```typescript
const statusStyles: Record<AppointmentStatus, string> = {
  PENDING: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400',
  CONFIRMED: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400',
  IN_PROGRESS: 'bg-purple-100 text-purple-800 dark:bg-purple-900/30 dark:text-purple-400',
  COMPLETED: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
  CANCELLED: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
  NO_SHOW: 'bg-gray-100 text-gray-800 dark:bg-gray-900/30 dark:text-gray-400',
};
```

### 7.4 Responsive Design Breakpoints

```typescript
// Mobile-first yaklaşım — base → sm → md → lg → xl
className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"

// Tailwind v4 breakpoints:
// sm:  640px  — Büyük telefon
// md:  768px  — Tablet
// lg:  1024px — Laptop
// xl:  1280px — Desktop
// 2xl: 1536px — Büyük ekran
```

**Kurallar:**
- Mobile-first yaz (base = mobil, breakpoint = büyük ekran) — *WHY: Mobil CSS daha az, büyük ekranda ekleme yapmak performans açısından verimli*
- Admin panel: `md` breakpoint'te sidebar collapse — *WHY: Tablet'te sidebar tam genişlikte çok yer kaplar*
- Public sayfalar: Tüm breakpoint'lerde test et — *WHY: SEO ve kullanıcı deneyimi açısından responsive zorunlu*
- `hidden md:block` pattern ile mobilde gizle — *WHY: Mobilde gereksiz element render etme*

### 7.5 Dark Mode

```typescript
// DOĞRU: Dark mode variant'ı olan class
className="bg-white dark:bg-gray-900 text-gray-900 dark:text-gray-100"

// DOĞRU: Tema token'ları zaten dark mode desteği içerir
className="bg-background text-foreground" // Otomatik dark mode

// YANLIŞ: Sadece light mode
// className="bg-white text-gray-900"  // ← Dark mode'da okunmaz
```

*WHY: `next-themes` ile tema değişimi CSS class bazlı çalışır. Her renk seçiminde dark variant'ını da düşün. Tema token'ları (`bg-background`, `text-foreground`) dark mode'u otomatik handle eder — mümkünse bunları tercih et.*

---

## 8. Auth Kuralları

### 8.1 Token Yönetimi

```
Auth Flow:
1. Login: POST /api/auth/login → Backend HttpOnly cookie set eder (Set-Cookie header)
2. Her request: Cookie otomatik gönderilir (withCredentials: true)
3. 401 response: Refresh token ile yeni access token al (POST /api/auth/refresh)
4. Refresh başarısız: Logout → login sayfasına yönlendir
```

**Kurallar:**
- Token'ı localStorage/sessionStorage'da SAKLAMA — *WHY: XSS ile çalınır. HttpOnly cookie JavaScript'ten erişilemez*
- Token'ı manual header'da GÖNDERME — *WHY: Cookie `withCredentials: true` ile otomatik gönderilir*
- Refresh logic'i API client interceptor'ında handle et — *WHY: Her component'te ayrı refresh logic yazmak DRY ihlali*
- Refresh sırasında diğer request'leri kuyruğa al — *WHY: Paralel 401'ler çoklu refresh request'i tetikler, token rotation'da theft detection'a yol açar*

```typescript
// lib/api-client.ts — Refresh queue pattern
let isRefreshing = false;
let refreshQueue: Array<(token: string) => void> = [];

apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401 && !error.config._retry) {
      if (isRefreshing) {
        // Zaten refresh yapılıyor — kuyruğa ekle
        return new Promise((resolve) => {
          refreshQueue.push(() => {
            resolve(apiClient(error.config));
          });
        });
      }

      isRefreshing = true;
      error.config._retry = true;

      try {
        await apiClient.post('/api/auth/refresh');
        // Kuyrukta bekleyenleri çalıştır
        refreshQueue.forEach((cb) => cb(''));
        refreshQueue = [];
        return apiClient(error.config);
      } catch {
        // Refresh başarısız — logout
        refreshQueue = [];
        window.location.href = '/login';
        return Promise.reject(error);
      } finally {
        isRefreshing = false;
      }
    }
    return Promise.reject(error);
  }
);
```

### 8.2 Role-Based Rendering

```typescript
// DOĞRU: useAuth hook ile role kontrol
export function useAuth() {
  const { data: user } = useQuery({
    queryKey: authKeys.me,
    queryFn: () => authApi.me(),
    staleTime: 5 * 60 * 1000,
    retry: false,
  });

  return {
    user: user?.data,
    isAdmin: user?.data?.role === Role.TENANT_ADMIN,
    isStaff: user?.data?.role === Role.STAFF,
    isClient: user?.data?.role === Role.CLIENT,
    isAuthenticated: !!user?.data,
  };
}

// Component'te kullanım:
export function AppointmentActions({ appointment }: Props) {
  const { isAdmin } = useAuth();

  return (
    <div>
      {isAdmin && (
        <Button onClick={() => updateStatus(appointment.id, 'CONFIRMED')}>
          Onayla
        </Button>
      )}
      {/* Herkes görebilir */}
      <AppointmentDetail appointment={appointment} />
    </div>
  );
}
```

**Kurallar:**
- Role bazlı UI gizleme güvenlik DEĞİLDİR — *WHY: Client-side role check bypass edilebilir. Asıl güvenlik backend'deki `@PreAuthorize`. Frontend sadece UX için gizler*
- Admin-only butonları role check ile göster/gizle — *WHY: Client kullanıcıya admin butonu göstermek kafa karıştırır*
- Role bilgisini prop drilling ile TAŞIMA — *WHY: `useAuth()` hook her yerde erişilebilir*

### 8.3 Route Guard Pattern

```typescript
// components/guards/auth-guard.tsx
"use client";

export function AuthGuard({ children, allowedRoles }: AuthGuardProps) {
  const { user, isAuthenticated } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (!isAuthenticated) {
      router.replace(`/login?redirect=${encodeURIComponent(pathname)}`);
      return;
    }
    if (allowedRoles && user && !allowedRoles.includes(user.role)) {
      router.replace('/unauthorized');
    }
  }, [isAuthenticated, user, allowedRoles, router, pathname]);

  if (!isAuthenticated || (allowedRoles && user && !allowedRoles.includes(user.role))) {
    return <LoadingSpinner />;
  }

  return <>{children}</>;
}

// Layout'ta kullanım:
// app/(admin)/layout.tsx
export default function AdminLayout({ children }: { children: ReactNode }) {
  return (
    <AuthGuard allowedRoles={[Role.TENANT_ADMIN]}>
      <AdminSidebar />
      <main>{children}</main>
    </AuthGuard>
  );
}
```

**Backend scope → Frontend route guard mapping:**

| Backend Scope | Frontend Route Group | Guard |
|---------------|---------------------|-------|
| `/api/public/**` | `(public)` | Guard yok |
| `/api/auth/**` | `(auth)` | Giriş yapılmışsa redirect |
| `/api/admin/**` | `(admin)` | `AuthGuard allowedRoles={[TENANT_ADMIN]}` |
| `/api/staff/**` | `(staff)` | `AuthGuard allowedRoles={[STAFF]}` |
| `/api/client/**` | `(client)` | `AuthGuard allowedRoles={[CLIENT]}` |

### 8.4 Login Sonrası Redirect

```typescript
// Login sayfasında redirect parametresini kullan
const searchParams = useSearchParams();
const redirect = searchParams.get('redirect') || getDefaultRedirect(user.role);

function getDefaultRedirect(role: string): string {
  switch (role) {
    case Role.TENANT_ADMIN: return '/admin/dashboard';
    case Role.STAFF: return '/staff/calendar';
    case Role.CLIENT: return '/client/appointments';
    default: return '/';
  }
}
```

*WHY: Kullanıcı `/admin/appointments` sayfasındayken session timeout olursa, login sonrası ana sayfaya değil kaldığı sayfaya dönmeli. `redirect` query param'ı bunu sağlar.*

### 8.5 Session Timeout UX

- Access token: 1 saat (backend JWT expiry)
- Token expire olduğunda: İlk 401 → refresh → başarılıysa devam
- Refresh başarısız: Toast mesajı "Oturumunuz sona erdi" + login'e yönlendir
- Form doldurulurken timeout: Form verisi kaybını ÖNLE — *WHY: Kullanıcı 30 dk form doldurduysa, logout sonrası veri kaybı kötü UX*
  - Login sonrası redirect ile aynı sayfaya dön
  - Form state localStorage'a düşür (hassas veri İÇERMEYEN formlar için)

---

## 9. Multi-Tenant Kuralları

### 9.1 Tenant Context Kullanımı

```typescript
// DOĞRU: useTenant() hook ile tenant bilgisine eriş
export function ServiceList() {
  const { tenant } = useTenant();
  const { data } = useServices();

  return (
    <div>
      <h1>{tenant.businessName} — Hizmetler</h1>
      {/* tenant.slug, tenant.businessType, tenant.settings */}
    </div>
  );
}

// YANLIŞ: Subdomain'i her component'te parse etme
// const slug = window.location.hostname.split('.')[0]; // ← YAPMA
```

*WHY: `TenantProvider` subdomain çözümlemeyi bir kez yapar, context'te saklar. Her component'te subdomain parse etmek tekrar ve hata riski. Middleware zaten tenant doğrulamasını yapmış, context güvenilirdir.*

### 9.2 API İsteklerinde Tenant

```typescript
// Tenant bilgisi subdomain'den otomatik çözümlenir
// API isteklerine tenant_id EKLEME — backend TenantFilter subdomain'den çözer

// DOĞRU: Subdomain üzerinden istek
// https://salon1.app.com/api/admin/services → TenantFilter slug=salon1 → tenantId çözer
apiClient.get('/api/admin/services');

// YANLIŞ: Manuel tenant_id gönderme
// apiClient.get('/api/admin/services', { params: { tenantId: 'xxx' } }); // ← YAPMA
```

*WHY: Backend `TenantFilter` subdomain'den `TenantContext` set eder. Frontend'den tenant_id göndermek gereksiz ve güvenlik riski (cross-tenant injection denemesi).*

### 9.3 Modül Guard Zorunluluğu

```typescript
// Modül gerektiren her route/component ModuleGuard ile sarmalanmalı
// features/blog/components/blog-list.tsx
export function BlogSection() {
  return (
    <ModuleGuard module={FeatureModule.BLOG} fallback={<ModuleUpgradePrompt module="Blog" />}>
      <BlogList />
    </ModuleGuard>
  );
}

// Sidebar'da modül kontrolü
export function AdminSidebar() {
  const { hasModule } = useModuleAccess();

  return (
    <nav>
      <SidebarLink href="/admin/appointments" icon={Calendar}>Randevular</SidebarLink>
      {hasModule(FeatureModule.BLOG) && (
        <SidebarLink href="/admin/blog" icon={FileText}>Blog</SidebarLink>
      )}
      {hasModule(FeatureModule.GALLERY) && (
        <SidebarLink href="/admin/gallery" icon={Image}>Galeri</SidebarLink>
      )}
    </nav>
  );
}
```

**Kurallar:**
- Backend'deki `@RequiresModule` ile birebir eşleşen frontend guard — *WHY: Backend zaten erişimi engelleyecektir ama kullanıcıya erişemeyeceği bir sayfayı göstermek kötü UX*
- TRIAL tenant'ta TÜM modüller açık — *WHY: Backend TRIAL'da tüm modülleri açar, frontend de aynı davranmalı*
- Modül kapalıysa upgrade prompt göster — *WHY: Kullanıcıya neden erişemediğini ve nasıl açabileceğini göster*
- `FeatureModule` enum backend ile birebir aynı olmalı — *WHY: Backend-frontend senkronizasyonu kırılırsa guard'lar yanlış çalışır*

### 9.4 Tenant-Scoped Cache

```typescript
// TanStack Query key'leri otomatik olarak tenant-scoped'dur
// Çünkü subdomain farklı → Origin farklı → QueryClient farklı instance

// AMA: Aynı tarayıcıda farklı tenant tab'ları açıksa
// localStorage/sessionStorage tenant-scoped olmalı
const STORAGE_KEY = `${tenant.slug}:sidebar-collapsed`;
localStorage.setItem(STORAGE_KEY, 'true');

// YANLIŞ: Tenant prefix olmadan storage
// localStorage.setItem('sidebar-collapsed', 'true'); // ← Tenant'lar arası çakışır
```

*WHY: localStorage domain bazlıdır, subdomain'ler aynı domain'i paylaşabilir. Tenant prefix olmadan salon1'in ayarları salon2'de görünür.*

---

## 10. API Kuralları

### 10.1 Request/Response Type Zorunluluğu

```typescript
// DOĞRU: Her API fonksiyonu tam typed
export const appointmentApi = {
  getList: (filters: AppointmentFilters) =>
    apiClient.get<ApiResponse<PagedResponse<AppointmentResponse>>>('/api/admin/appointments', {
      params: filters,
    }).then(res => res.data),

  getById: (id: string) =>
    apiClient.get<ApiResponse<AppointmentResponse>>(`/api/admin/appointments/${id}`)
      .then(res => res.data),

  create: (data: CreateAppointmentRequest) =>
    apiClient.post<ApiResponse<AppointmentResponse>>('/api/admin/appointments', data)
      .then(res => res.data),
};

// YANLIŞ: any kullanımı
// apiClient.get('/api/admin/appointments').then((res: any) => res.data);
```

*WHY: `any` TypeScript'in tüm tip güvenliğini kaldırır. Yanlış field erişimi runtime'a kadar fark edilmez. Tam typed API fonksiyonları IDE autocompletion ve compile-time hata yakalama sağlar.*

### 10.2 Error Handling Pattern

```typescript
// lib/api-client.ts — Error interceptor
apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ErrorResponse>) => {
    // X-Correlation-ID'yi error context'e ekle (Sentry tracking)
    const correlationId = error.response?.headers?.['x-correlation-id'];
    if (correlationId) {
      Sentry.setTag('correlation_id', correlationId);
    }

    // 401: refresh flow (§8.1'de tanımlı)
    // 403 PLAN_LIMIT_EXCEEDED: Upgrade prompt
    // 403 CLIENT_BLACKLISTED: Kara liste mesajı
    // 409 APPOINTMENT_CONFLICT: Çakışma mesajı
    // 404: "Kaynak bulunamadı"
    // 500: "Bir hata oluştu. Lütfen tekrar deneyin."
    // Diğer: Generic hata mesajı

    return Promise.reject(error);
  }
);

// ErrorCode → kullanıcı mesajı mapping
export function getErrorMessage(error: AxiosError<ErrorResponse>): string {
  const errorCode = error.response?.data?.code;
  const errorMessages: Record<ErrorCode, string> = {
    VALIDATION_ERROR: 'Lütfen form alanlarını kontrol ediniz.',
    INVALID_CREDENTIALS: 'E-posta veya şifre hatalı.',
    FORBIDDEN: 'Bu işlem için yetkiniz bulunmuyor.',
    PLAN_LIMIT_EXCEEDED: 'Plan limitinize ulaştınız. Planınızı yükseltin.',
    CLIENT_BLACKLISTED: 'Hesabınız kısıtlanmıştır. Lütfen işletmeyle iletişime geçin.',
    RESOURCE_NOT_FOUND: 'Aradığınız kayıt bulunamadı.',
    TENANT_NOT_FOUND: 'İşletme bulunamadı.',
    APPOINTMENT_CONFLICT: 'Bu zaman diliminde çakışma var.',
    DUPLICATE_RESOURCE: 'Bu kayıt zaten mevcut.',
    ACCOUNT_LOCKED: 'Hesabınız geçici olarak kilitlendi. 15 dakika sonra tekrar deneyin.',
    INTERNAL_ERROR: 'Bir hata oluştu. Lütfen tekrar deneyin.',
  };

  return errorCode ? errorMessages[errorCode] : 'Bir hata oluştu.';
}
```

*WHY: Backend `ErrorCode` enum'u → kullanıcı dostu Türkçe mesaj mapping. Teknik hata mesajı kullanıcıya GÖSTERİLMEMELİ. X-Correlation-ID Sentry'ye eklenmeli — backend loglarıyla eşleştirme için kritik.*

### 10.3 Loading & Error State

```typescript
// Her data-dependent component'te üç durumu handle et
export function AppointmentList() {
  const { data, isLoading, error } = useAppointments(filters);

  if (isLoading) return <AppointmentListSkeleton />; // ← Skeleton, spinner DEĞİL
  if (error) return <ErrorMessage error={error} onRetry={() => refetch()} />;
  if (!data?.data.length) return <EmptyState message="Henüz randevu bulunmuyor." />;

  return <DataTable data={data.data} columns={columns} />;
}
```

**Kurallar:**
- Loading: Skeleton UI tercih et (spinner değil) — *WHY: Skeleton layout shift önler, daha profesyonel UX*
- Error: Retry butonu göster — *WHY: Kullanıcı geçici hatayı kendisi çözebilir*
- Empty: Açıklayıcı mesaj + CTA (varsa) — *WHY: Boş tablo yerine yönlendirici mesaj daha iyi UX*
- Hiçbir durumu ATLAMA — *WHY: Atlanmış loading state = layout shift, atlanmış error = sessiz hata*

### 10.4 Pagination Pattern

```typescript
// Admin tablo pagination
interface PaginationParams {
  page: number;    // 0-indexed (backend convention)
  size: number;    // Default 20
  sort?: string;   // "date,desc" format
}

// URL searchParams ile pagination state
export function useTablePagination(defaultSize = 20) {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const page = Number(searchParams.get('page') ?? '0');
  const size = Number(searchParams.get('size') ?? String(defaultSize));
  const sort = searchParams.get('sort') ?? undefined;

  const setPage = (newPage: number) => {
    const params = new URLSearchParams(searchParams);
    params.set('page', String(newPage));
    router.push(`${pathname}?${params.toString()}`);
  };

  return { page, size, sort, setPage };
}
```

*WHY: Pagination state URL'de tutulması sayfa yenileme ve link paylaşımında durumu korur. `page=0` backend Spring `Pageable` convention ile eşleşir (0-indexed).*

### 10.5 X-Correlation-ID

```typescript
// lib/api-client.ts — Her request'e unique correlation ID ekle
import { v4 as uuidv4 } from 'uuid';

apiClient.interceptors.request.use((config) => {
  config.headers['X-Correlation-ID'] = uuidv4();
  return config;
});
```

*WHY: Backend `CorrelationIdFilter` aynı header'ı loglara ekler. Frontend → Backend → Log zincirinde tek bir request'i izlemeyi sağlar. Sentry error'larında backend loguyla eşleştirme için kritik.*

---

## 11. Performance Kuralları

### 11.1 Image Optimization

```typescript
// DOĞRU: next/image ile optimize
import Image from 'next/image';

<Image
  src={presignedUrl}
  alt={service.name}
  width={400}
  height={300}
  className="rounded-lg object-cover"
  placeholder="blur"
  blurDataURL={shimmerDataUrl}
/>

// YANLIŞ: HTML img tag
// <img src={imageUrl} />  // ← Boyut belirtilmemiş, lazy loading yok, format optimizasyonu yok
```

*WHY: `next/image` otomatik WebP/AVIF dönüşümü, lazy loading, responsive srcset üretir. CLS (Cumulative Layout Shift) width/height ile önlenir. `img` tag kullanmak LCP ve CLS metriklerini olumsuz etkiler.*

### 11.2 Dynamic Import (Code Splitting)

```typescript
// Ağır kütüphaneler dynamic import ile yükle
import dynamic from 'next/dynamic';

const FullCalendar = dynamic(() => import('@fullcalendar/react'), {
  ssr: false,
  loading: () => <CalendarSkeleton />,
});

const TiptapEditor = dynamic(() => import('@/features/blog/components/tiptap-editor'), {
  ssr: false,
  loading: () => <EditorSkeleton />,
});

const Recharts = dynamic(() => import('recharts').then(mod => ({ default: mod.AreaChart })), {
  ssr: false,
});
```

**Dynamic import edilmesi GEREKEN kütüphaneler:**
- FullCalendar (~200KB)
- Tiptap Editor (~150KB)
- Recharts (~100KB)
- react-dropzone + react-image-crop
- Framer Motion (kullanıldığı component bazında)

*WHY: Bu kütüphaneler initial bundle'ı şişirir. Kullanıcı blog editörünü görmeden 150KB JavaScript yüklenmesi gereksiz. Dynamic import ile sadece ihtiyaç duyulduğunda yüklenir.*

### 11.3 useMemo / useCallback Kuralları

```typescript
// DOĞRU: Expensive computation
const filteredAppointments = useMemo(
  () => appointments.filter(a => matchesFilters(a, filters)),
  [appointments, filters]
);

// DOĞRU: Referans stabilitesi gereken callback (child component'e prop olarak geçen)
const handleStatusChange = useCallback(
  (id: string, status: AppointmentStatus) => {
    updateStatusMutation.mutate({ id, status });
  },
  [updateStatusMutation]
);

// YANLIŞ: Her şeyi memo'la (premature optimization)
// const name = useMemo(() => user.firstName + ' ' + user.lastName, [user]);
// ← String concat memo'ya değmez
```

*WHY: `useMemo`/`useCallback` kendi başına maliyetlidir (dependency array comparison). Sadece expensive computation veya referans stabilitesi gerektiğinde kullan. Basit değerler için memo overhead > hesaplama maliyeti.*

### 11.4 Re-render Prevention

```typescript
// DOĞRU: Zustand selector ile sadece gerekli state'i al
const isCollapsed = useSidebarStore((state) => state.isCollapsed);
// → Sadece isCollapsed değiştiğinde re-render

// YANLIŞ: Tüm store'u destructure etme
// const { isCollapsed, toggle, open, close } = useSidebarStore();
// → Store'daki herhangi bir değer değiştiğinde re-render
```

*WHY: Zustand selector kullanmadan tüm store subscribe edilir. 10 field'lık store'da 1 field değiştiğinde tüm consumer component'ler re-render olur.*

### 11.5 Lazy Loading

```typescript
// Viewport dışındaki içerik lazy load
import { useInView } from 'react-intersection-observer';

export function ServiceCard({ service }: Props) {
  const { ref, inView } = useInView({ triggerOnce: true });

  return (
    <div ref={ref}>
      {inView ? (
        <Image src={service.imageUrl} alt={service.name} width={300} height={200} />
      ) : (
        <div className="h-[200px] w-[300px] animate-pulse bg-muted rounded" />
      )}
    </div>
  );
}
```

*WHY: next/image zaten lazy loading yapar, ama complex component'ler (harita, video, 3D) için IntersectionObserver ile manual lazy loading gerekli.*

---

## 12. Güvenlik Kuralları

### 12.0 Rate Limiting (Backend)

Backend endpoint-bazlı rate limiting uygular. Frontend bu limitleri aşmamak için dikkat etmelidir:

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

Limit aşılınca backend `429 Too Many Requests` döner:
```json
{
  "success": false,
  "error": "Çok fazla istek gönderildi. Lütfen bir süre bekleyin.",
  "code": "RATE_LIMIT_EXCEEDED",
  "timestamp": "..."
}
```

Frontend bu durumu Axios interceptor'da yakalamalı ve kullanıcıya uygun mesaj göstermelidir. `Retry-After` header'ı bekleme süresini saniye cinsinden belirtir.

*WHY: Rate limiting brute-force saldırıları ve kötüye kullanımı önler. Frontend, özellikle form submit butonlarını disable ederek ve debounce uygulayarak gereksiz istek gönderimini minimize etmelidir.*

### 12.1 XSS Koruması

```typescript
// DOĞRU: dangerouslySetInnerHTML KULLANMA — DOMPurify ile sanitize et
import DOMPurify from 'isomorphic-dompurify';

export function BlogContent({ htmlContent }: { htmlContent: string }) {
  const sanitized = DOMPurify.sanitize(htmlContent, {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 'ul', 'ol', 'li', 'a', 'h2', 'h3', 'blockquote', 'img'],
    ALLOWED_ATTR: ['href', 'src', 'alt', 'class'],
  });

  return <div dangerouslySetInnerHTML={{ __html: sanitized }} />;
}

// YANLIŞ: Sanitize olmadan HTML render
// <div dangerouslySetInnerHTML={{ __html: userInput }} />  // ← XSS açığı!
```

*WHY: Rich text editor (Tiptap) HTML üretir — backend'de saklanır, frontend'de render edilir. Sanitize edilmeden render etmek XSS saldırısına açık kapı bırakır. DOMPurify whitelist-based filtreleme yapar.*

### 12.2 CSRF Koruması

```
HttpOnly Cookie + SameSite=Lax + CORS = CSRF koruması

- HttpOnly: JavaScript cookie'ye erişemez → XSS ile token çalınamaz
- SameSite=Lax: Cross-site POST request'te cookie gönderilmez → CSRF önlenir
- CORS: Sadece izin verilen origin'lerden request kabul edilir
```

*WHY: Backend CSRF protection devre dışıdır (stateless JWT API). HttpOnly cookie + SameSite + CORS üçlüsü CSRF'i zaten engeller.*

### 12.3 Environment Variable Güvenliği

```typescript
// DOĞRU: Server-only secret → NEXT_PUBLIC_ prefix KULLANMA
// .env.local
DATABASE_URL=mysql://...        // Sadece server'da erişilebilir
JWT_SECRET=xxx                  // Sadece server'da erişilebilir

// DOĞRU: Client'a açık değerler → NEXT_PUBLIC_ prefix
NEXT_PUBLIC_API_URL=https://api.app.com
NEXT_PUBLIC_SENTRY_DSN=https://xxx@sentry.io/yyy

// YANLIŞ: Secret'ı client'a expose etme
// NEXT_PUBLIC_JWT_SECRET=xxx    // ← YAPMA! Client bundle'da görünür
// NEXT_PUBLIC_DATABASE_URL=xxx  // ← YAPMA!
```

*WHY: `NEXT_PUBLIC_` prefix'li env variable'lar client JavaScript bundle'ına inline edilir. Browser DevTools → Sources'tan okunabilir. Secret bilgiler asla `NEXT_PUBLIC_` prefix'i almamalıdır.*

### 12.4 Input Sanitization

```typescript
// URL parametrelerini sanitize et
const id = params.id;
if (!/^[a-f0-9-]{36}$/.test(id)) {
  notFound(); // UUID format kontrolü
}

// Arama sorgusunu sanitize et
const query = searchParams.get('q')?.trim().slice(0, 100) ?? '';
// Max uzunluk sınırı + trim

// YANLIŞ: URL parametresini doğrudan API'ye geçme
// apiClient.get(`/api/admin/appointments/${params.id}`);
// ← params.id manipüle edilmiş olabilir (path traversal)
```

*WHY: Frontend validation backend güvenliğinin yerini TUTMAZ ama ilk savunma hattıdır. Geçersiz input'ları early reject etmek gereksiz API call önler ve UX iyileştirir.*

### 12.5 Hassas Veri Gösterimi

```typescript
// Hasta kayıtları gibi hassas veriler backend'de AES-256 ile encrypt edilir
// Frontend'de backend çözümlenmiş veriyi gösterir — frontend decrypt YAPMAZ

// Hassas alanlar maskeli gösterilmeli:
function maskPhone(phone: string): string {
  return phone.replace(/(\d{3})\d{4}(\d{3})/, '$1****$2');
  // 5321234567 → 532****567
}

function maskEmail(email: string): string {
  const [local, domain] = email.split('@');
  return `${local.slice(0, 2)}***@${domain}`;
  // ahmet@email.com → ah***@email.com
}

// Tam veri gösterimi için explicit "Göster" butonu
export function SensitiveField({ value, label }: Props) {
  const [isVisible, setIsVisible] = useState(false);

  return (
    <div>
      <span>{label}: </span>
      <span>{isVisible ? value : maskValue(value)}</span>
      <Button variant="ghost" size="sm" onClick={() => setIsVisible(!isVisible)}>
        {isVisible ? <EyeOff size={16} /> : <Eye size={16} />}
      </Button>
    </div>
  );
}
```

*WHY: Hassas veriler (TC kimlik, telefon, e-posta) varsayılan olarak maskeli gösterilmeli. Omuz üzerinden bakma (shoulder surfing) koruması. Tam gösterim kullanıcı etkileşimi gerektirir.*

---

## 13. SEO Kuralları

### 13.1 Metadata Zorunlulukları

```typescript
// Her public sayfa generateMetadata fonksiyonu içermeli
// app/(public)/[slug]/services/page.tsx
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const tenant = await getTenant(params.slug);
  const services = await getServices(params.slug);

  return {
    title: `Hizmetlerimiz — ${tenant.businessName}`,
    description: `${tenant.businessName} hizmet listesi ve fiyatları`,
    openGraph: {
      title: `Hizmetlerimiz — ${tenant.businessName}`,
      description: `${tenant.businessName} hizmet listesi`,
      type: 'website',
      images: tenant.logoUrl ? [{ url: tenant.logoUrl }] : [],
    },
  };
}
```

**Kurallar:**
- Her public sayfa (`(public)` route group) `generateMetadata` içermeli — *WHY: SEO için title ve description zorunlu*
- Admin/staff/client sayfalarında metadata opsiyonel — *WHY: Bu sayfalar arama motorlarına kapalı*
- `robots.txt`'te admin/staff/client path'leri `Disallow` — *WHY: İç panellerin index'lenmesi güvenlik riski*

### 13.2 Semantic HTML

```typescript
// DOĞRU: Semantik HTML elemanları
<header>...</header>
<nav>...</nav>
<main>
  <article>
    <h1>Başlık</h1>
    <section>...</section>
  </article>
</main>
<footer>...</footer>

// YANLIŞ: Her yerde div
// <div class="header">
//   <div class="nav">
//     <div class="main">
```

*WHY: Semantik HTML arama motorlarının sayfa yapısını anlamasını sağlar. Ekran okuyucular için erişilebilirlik (accessibility) artırır. SEO sıralamasında pozitif sinyal.*

### 13.3 Structured Data (JSON-LD)

```typescript
// Hizmet detay sayfasında Service schema
export function ServiceJsonLd({ service, tenant }: Props) {
  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'Service',
    name: service.name,
    description: service.description,
    provider: {
      '@type': 'LocalBusiness',
      name: tenant.businessName,
    },
    offers: {
      '@type': 'Offer',
      price: service.price,
      priceCurrency: 'TRY',
    },
  };

  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }} />;
}
```

*WHY: Google Rich Results JSON-LD formatını okur. Hizmet, fiyat, işletme bilgisi zengin snippet olarak arama sonuçlarında gösterilir.*

### 13.4 ISR (Incremental Static Regeneration)

```typescript
// Public sayfalar ISR ile statik generate et
// app/(public)/[slug]/services/page.tsx
export const revalidate = 300; // 5 dakika

// Dinamik slug'lar için generateStaticParams
export async function generateStaticParams() {
  const tenants = await getActiveTenants();
  return tenants.map((t) => ({ slug: t.slug }));
}
```

*WHY: ISR ile public sayfalar statik HTML olarak sunulur — TTFB minimum, SEO maximum. 5 dakikada bir revalidation ile güncel kalır. Admin paneli SSR (dinamik), public sayfalar ISR (statik).*

---

## 14. Randevu Kuralları

### 14.1 Slot Reservation UX (5dk Hold)

```typescript
// Kullanıcı slot seçtiğinde → backend'e reservation isteği gönder
// Backend Redis SETNX ile 5 dakika hold
// Frontend countdown timer göster

export function SlotReservation({ slotId, onExpire }: Props) {
  const [remaining, setRemaining] = useState(300); // 5 dakika

  useEffect(() => {
    const interval = setInterval(() => {
      setRemaining((prev) => {
        if (prev <= 1) {
          clearInterval(interval);
          onExpire(); // Süre doldu — slot serbest
          toast.warning('Seçtiğiniz randevu slotu süresi doldu. Lütfen tekrar seçiniz.');
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(interval);
  }, [onExpire]);

  return (
    <div className="text-sm text-muted-foreground">
      Slot {Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, '0')} süre ile rezerve edildi
    </div>
  );
}
```

**Kurallar:**
- Slot seçildiğinde backend'e reservation API call yap — *WHY: Redis SETNX ile atomik hold sağlanır, iki kullanıcı aynı slotu seçemez*
- 5 dakika countdown göster — *WHY: Kullanıcı baskı hissetsin ama yeterli süre olsun*
- Süre dolduğunda slot serbest bırakılır + toast uyarısı — *WHY: Kullanıcı bilgilendirilmeli*
- "Bu slot başka biri tarafından seçildi" mesajı — *WHY: Slot zaten reserved ise açık mesaj ver*

### 14.2 Randevu Oluşturma Akışı

```
1. Hizmet seç (multi-select)
2. Tarih seç (takvim)
3. Personel seç (opsiyonel — backend otomatik atayabilir)
4. Müsait slot seç → 5dk reservation başlar
5. Müşteri bilgileri (public booking) / otomatik (logged-in)
6. Özet & Onayla
```

**Kurallar:**
- Multi-service seçimde toplam süre hesapla — *WHY: Art arda hizmetlerin toplam süresi müsaitlik kontrolünde kullanılır*
- Hizmet seçimine göre personel filtrele — *WHY: Backend Staff-Service many-to-many ilişkisi, her personel her hizmeti veremez*
- Auto-confirm ayarına dikkat — *WHY: `SiteSettings.autoConfirmAppointments = true` ise randevu direkt CONFIRMED, false ise PENDING*
- `availabilityKeys` staleTime: 0 — *WHY: Müsaitlik gerçek zamanlı doğruluk gerektirir, cache'lenmiş müsaitlik çift randevuya yol açar*

### 14.3 Reschedule Flow

```typescript
// Randevu yeniden planlaması — yeni tarih/saat/personel seçimi
// Mevcut randevunun bilgileri pre-fill edilir
export function RescheduleDialog({ appointment }: { appointment: AppointmentResponse }) {
  // 1. Aynı hizmetler otomatik seçili
  // 2. Yeni tarih/saat/personel seçimi
  // 3. Backend: eski randevu CANCELLED + yeni randevu oluştur (veya update — backend stratejisine göre)
  // ...
}
```

*WHY: Reschedule mevcut randevuyu iptal edip yenisini oluşturur (backend'in reschedule endpoint'i bunu handle eder). Frontend bu akışı tek bir dialog/sayfa ile sunar.*

### 14.4 Status State Machine (Frontend)

```typescript
// Backend ile birebir aynı geçiş kuralları
const validTransitions: Record<AppointmentStatus, AppointmentStatus[]> = {
  PENDING: ['CONFIRMED', 'CANCELLED'],
  CONFIRMED: ['IN_PROGRESS', 'CANCELLED', 'NO_SHOW'],
  IN_PROGRESS: ['COMPLETED'],
  COMPLETED: [],
  CANCELLED: [],
  NO_SHOW: [],
};

export function canTransition(from: AppointmentStatus, to: AppointmentStatus): boolean {
  return validTransitions[from]?.includes(to) ?? false;
}

// UI'da sadece geçerli geçişleri buton olarak göster
export function StatusActions({ appointment }: Props) {
  const allowed = validTransitions[appointment.status];

  return (
    <div className="flex gap-2">
      {allowed.map((status) => (
        <Button key={status} onClick={() => updateStatus(appointment.id, status)}>
          {statusActionLabels[status]}
        </Button>
      ))}
    </div>
  );
}
```

*WHY: Kullanıcıya geçersiz durum geçişi butonu GÖSTERME. Backend zaten engelleyecektir ama kötü UX. Frontend state machine backend ile birebir aynı olmalı.*

### 14.5 DST (Yaz/Kış Saati) Geçiş Kontrolü

```typescript
// Randevu tarihinde DST geçişi varsa uyarı göster
import { getTimezoneOffset } from 'date-fns-tz';

function isDstTransitionDate(date: Date, timezone: string): boolean {
  const current = getTimezoneOffset(timezone, date);
  const nextDay = getTimezoneOffset(timezone, addDays(date, 1));
  return current !== nextDay;
}

// Booking formunda:
if (isDstTransitionDate(selectedDate, tenant.timezone)) {
  toast.warning('Seçtiğiniz tarihte saat değişikliği (yaz/kış saati) var. Lütfen saati doğrulayın.');
}
```

*WHY: DST geçiş gününde saat ileri/geri alınır. 02:30 randevusu geçiş günü var olmayabilir veya iki kez oluşabilir. Kullanıcıyı uyarmak hatalı randevu önler.*

---

## 15. Dosya & Görsel Kuralları

### 15.1 Presigned URL Kullanımı (KRİTİK)

```typescript
// DOĞRU: Backend'den presigned URL al, doğrudan S3 public URL KULLANMA
export function SecureImage({ fileKey, alt, ...props }: SecureImageProps) {
  const { data: presignedUrl } = useQuery({
    queryKey: ['presigned-url', fileKey],
    queryFn: () => fileApi.getPresignedUrl(fileKey),
    staleTime: 10 * 60 * 1000, // 10 dk (presigned URL genelde 15dk geçerli)
    enabled: !!fileKey,
  });

  if (!presignedUrl) return <ImageSkeleton />;

  return <Image src={presignedUrl} alt={alt} {...props} />;
}

// YANLIŞ: S3 public URL kullanma
// <Image src={`https://s3.amazonaws.com/bucket/${fileKey}`} />  // ← Güvenlik açığı!
```

*WHY: S3 public URL ile tüm dosyalar herkese açık olur. Presigned URL ömürlüdür (15dk) ve izinsiz erişimi engeller. Tenant izolasyonu presigned URL üzerinden sağlanır — tenant A, tenant B'nin dosyasına erişemez.*

### 15.2 Upload Flow

```typescript
// 1. Frontend: Dosya seçimi + client-side validation
// 2. Frontend → Backend: multipart/form-data POST
// 3. Backend: 5 katmanlı güvenlik kontrolü (size, extension, MIME, dimension, UUID rename)
// 4. Backend → S3: Tenant-scoped path ile yükleme
// 5. Backend → Frontend: Dosya key'i döner (S3 path)

// Client-side pre-validation (gereksiz upload önleme)
const MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];

function validateFile(file: File): string | null {
  if (file.size > MAX_FILE_SIZE) return 'Dosya boyutu 5MB\'ı geçemez';
  if (!ALLOWED_TYPES.includes(file.type)) return 'Desteklenmeyen dosya formatı';
  return null;
}
```

**Kurallar:**
- Client-side validation backend validation'ın yerini TUTMAZ — *WHY: Frontend validation bypass edilebilir. Ama gereksiz yüklemeyi önlemek için UX açısından gerekli*
- Upload sırasında progress bar göster — *WHY: 5MB dosya yüklemesi zaman alır, kullanıcı ne olduğunu bilmeli*
- Upload başarılı → dosya key'ini form state'ine kaydet — *WHY: Entity kaydı sırasında dosya key'i referans olarak gönderilir*

### 15.3 Image Crop

```typescript
// Profil fotoğrafı ve logo yüklemede react-image-crop kullan
import ReactCrop from 'react-image-crop';

// 1. Dosya seçimi (react-dropzone)
// 2. Crop dialog (react-image-crop) — sabit aspect ratio
// 3. Crop sonucu → Canvas → Blob → Upload
```

*WHY: Kullanıcıların kırpmadan yüklediği fotoğraflar farklı boyut/oranla gelir. Crop ile tutarlı görüntü oranı sağlanır (logo: 1:1, banner: 16:9).*

---

## 16. Anti-Pattern'ler (YAPMA)

Her madde: ❌ Yanlış → ✅ Doğru — *WHY: gerekçe*

### Mimari & Component

1. ❌ Tüm sayfayı `"use client"` yapma → ✅ Sadece interaktif kısmı Client Component yap — *WHY: Tüm sayfanın server rendering avantajı kaybedilir, bundle size artar*

2. ❌ `export default function` → ✅ Named `export function` — *WHY: Default export import'ta herhangi isimle çağrılabilir, tutarsızlık yaratır. Named export refactoring-safe*

3. ❌ Prop drilling 3+ seviye → ✅ Context, Zustand veya composition pattern — *WHY: Her ara component'in değişmesi gereken props'u taşıması bakım maliyetini artırır*

4. ❌ `any` kullanımı → ✅ Proper TypeScript type — *WHY: `any` tüm tip güvenliğini kaldırır, runtime hataları derleme zamanında yakalanamaz*

5. ❌ TypeScript `enum` → ✅ `as const` + type extraction — *WHY: Enum runtime object üretir, tree-shaking'i engeller, bundle size artırır*

6. ❌ Nested ternary (3+ seviye) → ✅ Early return veya switch/map — *WHY: Okunabilirlik sıfır, hata ayıklama imkansız*

### Data Fetching & State

7. ❌ Component içinde `fetch`/`axios` doğrudan çağırma → ✅ Feature `api/` klasöründe query hook — *WHY: Aynı API call farklı component'lerde tekrarlanır, cache paylaşılmaz*

8. ❌ Query key string literal → ✅ Query key factory pattern — *WHY: String literal refactoring'de kırılır, typo riski, invalidation tutarsız*

9. ❌ staleTime belirtmeme (default 0) → ✅ Her query'ye uygun staleTime — *WHY: Default 0 = her focus'ta refetch, gereksiz API yükü*

10. ❌ Mutation sonrası invalidation unutma → ✅ İlgili TÜM query'leri invalidate et — *WHY: Stale data gösterilir, kullanıcı yeni oluşturduğu kaydı göremez*

11. ❌ Her state'i Zustand'da tutma → ✅ Server state: TanStack Query, URL state: searchParams, UI state: Zustand — *WHY: TanStack Query zaten server state cache'i yönetir, Zustand'a kopyalamak senkronizasyon sorunu yaratır*

12. ❌ Form state'ini Zustand'da yönetme → ✅ React Hook Form kendi state'ini yönetir — *WHY: İki state kaynağı senkronizasyon karmaşıklığı, RHF zaten optimize re-render yapar*

13. ❌ Tüm Zustand store'u destructure (`const { a, b, c } = useStore()`) → ✅ Selector kullan (`const a = useStore(s => s.a)`) — *WHY: Destructure ile tüm store subscribe edilir, her değişiklikte tüm consumer re-render*

### API & Güvenlik

14. ❌ Token'ı localStorage'da saklama → ✅ HttpOnly Cookie — *WHY: XSS ile localStorage'dan çalınır, HttpOnly cookie JavaScript'ten erişilemez*

15. ❌ API response'u `any` olarak kullanma → ✅ Generic typed response — *WHY: Tip güvenliği kaybı, yanlış field erişimi runtime'a kadar fark edilmez*

16. ❌ Error state'i handle etmeme → ✅ Loading + Error + Empty + Data dört durumu handle et — *WHY: Error state atlanırsa kullanıcı neden veri göremediğini bilemez, sessiz hata*

17. ❌ `dangerouslySetInnerHTML` sanitize olmadan kullanma → ✅ DOMPurify sanitization — *WHY: XSS açığı, kullanıcı-girişli HTML tehlikeli script içerebilir*

18. ❌ Secret'ı `NEXT_PUBLIC_` env variable'da tutma → ✅ Server-only env variable — *WHY: `NEXT_PUBLIC_` prefix client bundle'a inline edilir, DevTools'tan okunabilir*

19. ❌ S3 public URL ile dosya gösterme → ✅ Backend presigned URL — *WHY: Public URL ile tüm dosyalar herkese açık, tenant izolasyonu kırılır*

20. ❌ URL parametresini sanitize etmeden API'ye geçme → ✅ Format validation (UUID regex) — *WHY: Path traversal, injection saldırı vektörü*

### Performance

21. ❌ HTML `<img>` tag → ✅ `next/image` — *WHY: Boyut belirtilmemiş (CLS), lazy loading yok, format optimizasyonu yok*

22. ❌ Ağır kütüphaneyi statik import → ✅ `dynamic()` ile dynamic import — *WHY: FullCalendar (200KB), Tiptap (150KB) initial bundle'ı şişirir, kullanılmasa bile yüklenir*

23. ❌ Her şeyi `useMemo`/`useCallback` ile sarma → ✅ Sadece expensive computation ve referans stabilitesi gerektiğinde — *WHY: Memo kendi maliyeti var (dependency comparison), basit değerler için overhead > fayda*

24. ❌ Unbounded liste render (pagination yok) → ✅ Tüm listelerde pagination veya infinite scroll — *WHY: 10.000 satır DOM'a eklemek tarayıcıyı çökertir*

### Stil & UX

25. ❌ Hardcode renk (`bg-blue-600`) → ✅ Tema token'ı (`bg-primary`) — *WHY: Tema değişiminde, dark mode'da, tenant override'da hardcode renk bozulur*

26. ❌ Spinner loading → ✅ Skeleton UI — *WHY: Skeleton layout shift önler, content yer tutucusu olarak daha iyi UX sağlar*

27. ❌ `window.alert()` / `window.confirm()` → ✅ Custom modal/dialog (shadcn/ui AlertDialog) — *WHY: Native dialog stil'i kontrol edilemez, tema ile uyumsuz, mobilde kötü UX*

### Multi-Tenant

28. ❌ Subdomain'i her component'te parse etme → ✅ `useTenant()` hook — *WHY: Tekrar, hata riski, middleware zaten çözmüş*

29. ❌ API isteğine manuel `tenantId` gönderme → ✅ Subdomain otomatik çözümleme — *WHY: Backend TenantFilter subdomain'den çözer, manuel gönderim cross-tenant injection riski*

30. ❌ Modül guard olmadan modül-bağımlı içerik gösterme → ✅ `ModuleGuard` component — *WHY: Modülü olmayan tenant'a kilitli sayfayı göstermek kötü UX, backend zaten 403 döner*

31. ❌ localStorage tenant prefix olmadan kullanma → ✅ `{tenant.slug}:key` prefix — *WHY: Aynı domain altında farklı tenant'lar storage paylaşır, veri çakışması*

### Randevu

32. ❌ Müsaitlik slotlarını cache'leme → ✅ `staleTime: 0` (her seferinde fresh fetch) — *WHY: Cache'lenmiş müsaitlik veri çift randevuya yol açar, gerçek zamanlı doğruluk şart*

33. ❌ Geçersiz durum geçişi butonu gösterme → ✅ `validTransitions` map'e göre sadece geçerli butonlar — *WHY: Kullanıcı COMPLETED randevuyu iptal edemez, buton göstermek kafa karıştırır*

34. ❌ DST geçiş gününde uyarı vermeme → ✅ DST detection + toast warning — *WHY: 02:30 randevusu geçiş günü var olmayabilir, kullanıcı şaşırır*

35. ❌ Slot reservation countdown göstermeme → ✅ 5dk countdown timer — *WHY: Kullanıcı ne kadar süresi kaldığını bilmeli, süre dolunca slot kaybolur*

---

## Referanslar

- **Mimari detaylar:** `FRONTEND_ARCHITECTURE.md` — 30 bölüm, detaylı TypeScript/TSX kod örnekleri
- **Backend kuralları:** `CLAUDE.md` — Backend geliştirme kuralları
- **Backend mimari:** `BACKEND_ARCHITECTURE.md` — 28 bölüm, backend detayları
