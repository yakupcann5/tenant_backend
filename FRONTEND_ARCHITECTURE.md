# Next.js + React — Multi-Tenant SaaS Frontend Mimarisi

## 1. Genel Bakış & Tech Stack

Bu doküman, **Kotlin + Spring Boot** backend'i (bkz. `BACKEND_ARCHITECTURE.md`) ile entegre çalışacak **Next.js** tabanlı multi-tenant SaaS frontend mimarisini tanımlar. Platform; güzellik klinikleri, diş klinikleri, berber dükkanları, kuaför salonları, diyetisyenler, fizyoterapistler, masaj salonları ve veterinerler için randevu yönetim sistemi sunar.

### Tech Stack

| Katman | Teknoloji | Versiyon |
|--------|-----------|----------|
| Framework | Next.js (App Router) | 15+ |
| UI Library | React | 19+ |
| Dil | TypeScript | 5.5+ (strict mode) |
| Styling | Tailwind CSS | v4 |
| UI Components | shadcn/ui (Radix primitives) | latest |
| Data Fetching | TanStack Query | v5 |
| Data Table | TanStack Table (headless) | v8 |
| Forms | React Hook Form + Zod | latest |
| Client State | Zustand | v5 |
| Auth / Token | HttpOnly Cookie (JWT from Spring Boot) | — |
| Tarih/Saat | date-fns + date-fns-tz | v4 |
| Rich Text | Tiptap | v2 |
| Grafikler | Recharts | v2 |
| Takvim | FullCalendar | v6 |
| Animasyon | Framer Motion | v11 |
| Toast | Sonner | latest |
| Dosya Yükleme | react-dropzone + react-image-crop | latest |
| İkonlar | Lucide React | latest |
| i18n | next-intl | latest |
| Dark Mode | next-themes | latest |
| SEO | SSG + ISR (Next.js built-in) | — |
| Real-time | Polling (TanStack Query refetchInterval) | — |
| Error Tracking | Sentry (@sentry/nextjs) | latest |
| Package Manager | pnpm | v9+ |
| Deployment | Docker (standalone output) | — |
| Code Quality | ESLint + Prettier + Husky + lint-staged | — |

### Mimari Genel Bakış

```
┌─────────────────────────────────────────────────────────────────────┐
│                        KULLANICI (Tarayıcı)                        │
│  salon1.app.com / klinik2.app.com / custom-domain.com              │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
                    ┌───────────▼───────────┐
                    │   Nginx Reverse Proxy  │
                    │   (TLS + Headers)      │
                    └───────┬───────┬────────┘
                            │       │
              ┌─────────────▼──┐ ┌──▼─────────────┐
              │  Next.js App   │ │  Spring Boot    │
              │  (Port 3000)   │ │  (Port 8080)    │
              │                │ │                  │
              │  ┌───────────┐ │ │  /api/public/**  │
              │  │ SSR/SSG   │ │ │  /api/auth/**    │
              │  │ Pages     │ │ │  /api/admin/**   │
              │  └───────────┘ │ │  /api/client/**  │
              │  ┌───────────┐ │ │  /api/staff/**   │
              │  │ API Client│─┼─│  /api/platform/**│
              │  │ (Axios)   │ │ │  /api/webhooks/**│
              │  └───────────┘ │ │                  │
              └────────────────┘ └──────┬───────────┘
                                        │
                              ┌─────────▼─────────┐
                              │   MySQL + Redis    │
                              └───────────────────-┘
```

### Backend ile İlişki

Frontend ve backend **ayrı repository**'lerde geliştirilir. İletişim tamamen REST API üzerinden sağlanır:

- **Frontend:** Kullanıcı arayüzü, SEO, client-side state, form validasyonu
- **Backend:** İş mantığı, veri yönetimi, güvenlik, bildirimler, ödeme
- **Senkronizasyon noktaları:** Enum'lar, DTO type'ları, API endpoint'leri, hata kodları

> **KRİTİK:** Frontend ve backend arasındaki enum, DTO ve hata kodu tanımları birebir eşleşmelidir. Backend'deki herhangi bir değişiklik frontend'e de yansıtılmalıdır.

---

## 2. Multi-Tenant Strateji (Frontend)

### 2.1 Subdomain Çözümleme

Backend'deki `TenantFilter` nasıl subdomain'den tenant çözümlüyorsa, frontend de aynı mantıkla çalışır. Next.js middleware'i her istekte subdomain'i çözümler ve tenant bilgisini context'e aktarır.

```
İstek akışı (Frontend):
  salon1.app.com → Next.js Middleware
                         │
              ┌──────────▼──────────┐
              │  Subdomain çıkar    │
              │  "salon1" → slug    │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │  /api/public/tenant  │
              │  config fetch        │
              │  (backend'den)       │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │  TenantProvider      │
              │  (React Context)     │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │  Sayfa render        │
              │  (tenant-aware)      │
              └─────────────────────┘
```

### 2.2 Middleware — Subdomain Çözümleme

```typescript
// middleware.ts
import { NextRequest, NextResponse } from "next/server";
import createMiddleware from "next-intl/middleware";
import { locales, defaultLocale } from "@/lib/i18n/config";

const PLATFORM_DOMAIN = process.env.NEXT_PUBLIC_PLATFORM_DOMAIN!; // "app.com"
const EXCLUDED_SUBDOMAINS = new Set(["www", "api", "admin", "platform"]);

export function middleware(request: NextRequest) {
  const hostname = request.headers.get("host") || "";
  const url = request.nextUrl.clone();

  // 1. Subdomain çıkar
  const slug = extractTenantSlug(hostname);

  if (slug) {
    // 2. Tenant slug'ı header'a ekle (layout'ta kullanılacak)
    const response = NextResponse.next();
    response.headers.set("x-tenant-slug", slug);

    // 3. Locale middleware ile birleştir
    return handleIntl(request, response, slug);
  }

  // Platform domain'i (app.com) — landing page
  if (isPlatformDomain(hostname)) {
    return handleIntl(request);
  }

  // Custom domain — backend'den tenant çözümle
  const response = NextResponse.next();
  response.headers.set("x-custom-domain", hostname);
  return handleIntl(request, response);
}

function extractTenantSlug(hostname: string): string | null {
  // salon1.app.com → "salon1"
  // localhost:3000 → null (geliştirme ortamı)
  const parts = hostname.split(".");

  // Geliştirme ortamı: NEXT_PUBLIC_DEV_TENANT_SLUG kullan
  if (hostname.includes("localhost")) {
    return process.env.NEXT_PUBLIC_DEV_TENANT_SLUG || null;
  }

  if (parts.length >= 3) {
    const subdomain = parts[0];
    if (!EXCLUDED_SUBDOMAINS.has(subdomain)) {
      return subdomain;
    }
  }

  return null;
}

function isPlatformDomain(hostname: string): boolean {
  return hostname === PLATFORM_DOMAIN || hostname === `www.${PLATFORM_DOMAIN}`;
}

function handleIntl(
  request: NextRequest,
  response?: NextResponse,
  tenantSlug?: string
): NextResponse {
  // next-intl middleware entegrasyonu
  const intlMiddleware = createMiddleware({
    locales,
    defaultLocale,
    localePrefix: "as-needed",
  });

  return intlMiddleware(request);
}

export const config = {
  matcher: [
    // Statik dosyaları ve API route'larını hariç tut
    "/((?!_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|jpeg|gif|webp)$).*)",
  ],
};
```

### 2.3 TenantProvider — React Context

```typescript
// lib/providers/tenant-provider.tsx
"use client";

import {
  createContext,
  useContext,
  useState,
  useEffect,
  type ReactNode,
} from "react";
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";
import type { TenantConfig, BusinessType } from "@/types/tenant";

interface TenantContextValue {
  tenant: TenantConfig | null;
  isLoading: boolean;
  error: Error | null;
  slug: string;
  businessType: BusinessType;
}

const TenantContext = createContext<TenantContextValue | null>(null);

interface TenantProviderProps {
  children: ReactNode;
  slug: string;
  initialData?: TenantConfig;
}

export function TenantProvider({
  children,
  slug,
  initialData,
}: TenantProviderProps) {
  const {
    data: tenant,
    isLoading,
    error,
  } = useQuery({
    queryKey: ["tenant", "config", slug],
    queryFn: () => apiClient.get<TenantConfig>(`/api/public/tenant/config`),
    initialData,
    staleTime: 15 * 60 * 1000, // 15 dk — SiteSettings backend'de 15 dk cache'lenir
    gcTime: 30 * 60 * 1000,
    retry: 2,
  });

  const value: TenantContextValue = {
    tenant: tenant ?? null,
    isLoading,
    error: error as Error | null,
    slug,
    businessType: tenant?.businessType ?? "GENERAL",
  };

  return (
    <TenantContext.Provider value={value}>{children}</TenantContext.Provider>
  );
}

export function useTenant(): TenantContextValue {
  const context = useContext(TenantContext);
  if (!context) {
    throw new Error("useTenant must be used within TenantProvider");
  }
  return context;
}
```

### 2.4 Tenant Config Tipi

```typescript
// types/tenant.ts
export type BusinessType =
  | "BEAUTY_CLINIC"
  | "DENTAL_CLINIC"
  | "BARBER_SHOP"
  | "HAIR_SALON"
  | "DIETITIAN"
  | "PHYSIOTHERAPIST"
  | "MASSAGE_SALON"
  | "VETERINARY"
  | "GENERAL";

export type SubscriptionPlan =
  | "TRIAL"
  | "STARTER"
  | "PROFESSIONAL"
  | "BUSINESS"
  | "ENTERPRISE";

export interface TenantConfig {
  id: string;
  slug: string;
  name: string;
  businessType: BusinessType;
  phone: string;
  email: string;
  address: string;
  logoUrl: string | null;
  plan: SubscriptionPlan;
  isActive: boolean;
  settings: SiteSettings | null;
  enabledModules: FeatureModule[];
}

// Backend: com.aesthetic.backend.dto.response.SiteSettingsResponse
export interface SiteSettings {
  id: string;
  siteName: string;
  phone: string;
  email: string;
  address: string;
  whatsapp: string;
  instagram: string;
  facebook: string;
  twitter: string;
  youtube: string;
  mapEmbedUrl: string;
  timezone: string; // "Europe/Istanbul" (IANA timezone)
  locale: string; // "tr"
  cancellationPolicyHours: number; // default 24
  defaultSlotDurationMinutes: number; // default 30
  themeSettings: string; // JSON string — parse edilerek ThemeSettings'e dönüştürülür
  createdAt: string | null; // ISO 8601
  updatedAt: string | null; // ISO 8601
}

export interface ThemeSettings {
  primaryColor: string;
  secondaryColor: string;
  accentColor: string;
  fontFamily: string | null;
  borderRadius: string; // "0.5rem"
  logoUrl: string | null;
  faviconUrl: string | null;
  customCss: string | null;
}
```

### 2.5 Tenant Config Cache Stratejisi

| Veri | TTL | Gerekçe |
|------|-----|---------|
| TenantConfig (slug çözümleme) | 15 dk | Backend'de SiteSettings 15 dk cache'lenir |
| enabledModules | 15 dk | Modül değişikliği nadir |
| themeSettings | 15 dk | Tema değişikliği nadir |

> **Backend referans:** `BACKEND_ARCHITECTURE.md §18.2` — SiteSettings 15 dk Redis cache TTL

---

## 3. Proje Yapısı

### 3.1 Kök Dizin

```
aesthetic-clinic-frontend/
├── .env.example              # Ortam değişkenleri şablonu
├── .env.local                # Yerel geliştirme (git'e DAHİL DEĞİL)
├── .eslintrc.cjs             # ESLint konfigürasyonu
├── .prettierrc               # Prettier konfigürasyonu
├── .husky/                   # Git hook'ları
│   ├── pre-commit            # lint-staged çalıştır
│   └── commit-msg            # Commit mesajı formatı kontrol
├── Dockerfile                # Production build
├── docker-compose.yml        # Backend ile birlikte çalıştırma
├── next.config.ts            # Next.js konfigürasyonu
├── tailwind.config.ts        # Tailwind CSS v4 konfigürasyonu
├── tsconfig.json             # TypeScript konfigürasyonu (strict: true)
├── components.json           # shadcn/ui konfigürasyonu
├── package.json
├── pnpm-lock.yaml
└── src/
    ├── app/                  # Next.js App Router
    ├── components/           # Paylaşılan UI bileşenleri
    ├── features/             # Feature-bazlı modüller
    ├── hooks/                # Paylaşılan custom hook'lar
    ├── lib/                  # Utility, config, provider'lar
    ├── types/                # Global TypeScript tipleri
    ├── styles/               # Global stiller
    └── messages/             # i18n çeviri dosyaları
```

### 3.2 `src/app/` — Route Organizasyonu

```
src/app/
├── layout.tsx                    # Root layout (Providers, ThemeProvider)
├── not-found.tsx                 # 404 sayfası
├── error.tsx                     # Global error boundary
├── loading.tsx                   # Global loading
│
├── (public)/                     # Herkesin erişebildiği sayfalar (SEO önemli)
│   ├── layout.tsx                # Public layout (navbar, footer)
│   ├── page.tsx                  # Ana sayfa (tenant landing)
│   ├── services/
│   │   ├── page.tsx              # Hizmet listesi
│   │   └── [slug]/
│   │       └── page.tsx          # Hizmet detay
│   ├── blog/
│   │   ├── page.tsx              # Blog listesi
│   │   └── [slug]/
│   │       └── page.tsx          # Blog yazısı detay
│   ├── gallery/
│   │   └── page.tsx              # Galeri
│   ├── reviews/
│   │   └── page.tsx              # Değerlendirmeler
│   ├── products/
│   │   ├── page.tsx              # Ürün listesi
│   │   └── [slug]/
│   │       └── page.tsx          # Ürün detay
│   ├── booking/
│   │   └── page.tsx              # Online randevu alma
│   └── contact/
│       └── page.tsx              # İletişim formu
│
├── (auth)/                       # Kimlik doğrulama sayfaları
│   ├── layout.tsx                # Auth layout (minimal, centered)
│   ├── login/
│   │   └── page.tsx              # Giriş
│   ├── register/
│   │   └── page.tsx              # Kayıt
│   ├── forgot-password/
│   │   └── page.tsx              # Şifre sıfırlama isteği
│   ├── reset-password/
│   │   └── page.tsx              # Şifre sıfırlama
│   └── verify-email/
│       └── page.tsx              # Email doğrulama
│
├── (admin)/                      # TENANT_ADMIN paneli
│   ├── layout.tsx                # Admin layout (Sidebar + Header)
│   └── admin/
│       ├── page.tsx              # Dashboard
│       ├── appointments/
│       │   ├── page.tsx          # Randevu listesi
│       │   ├── [id]/
│       │   │   └── page.tsx      # Randevu detay
│       │   └── calendar/
│       │       └── page.tsx      # Takvim görünümü
│       ├── services/
│       │   └── page.tsx          # Hizmet yönetimi
│       ├── staff/
│       │   ├── page.tsx          # Personel listesi
│       │   └── [id]/
│       │       └── page.tsx      # Personel detay
│       ├── clients/
│       │   ├── page.tsx          # Müşteri listesi
│       │   └── [id]/
│       │       └── page.tsx      # Müşteri detay + geçmiş
│       ├── blog/
│       │   ├── page.tsx          # Blog yazıları yönetimi
│       │   ├── new/
│       │   │   └── page.tsx      # Yeni yazı
│       │   └── [id]/
│       │       └── edit/
│       │           └── page.tsx  # Yazı düzenleme
│       ├── products/
│       │   └── page.tsx          # Ürün yönetimi
│       ├── gallery/
│       │   └── page.tsx          # Galeri yönetimi
│       ├── reviews/
│       │   └── page.tsx          # Değerlendirme yönetimi
│       ├── patient-records/
│       │   └── page.tsx          # Hasta kayıtları (modül)
│       ├── reports/
│       │   └── page.tsx          # Raporlar (modül)
│       ├── notifications/
│       │   ├── page.tsx          # Bildirim listesi
│       │   └── templates/
│       │       └── page.tsx      # Bildirim şablonları
│       └── settings/
│           ├── page.tsx          # Genel ayarlar
│           ├── theme/
│           │   └── page.tsx      # Tema özelleştirme
│           ├── working-hours/
│           │   └── page.tsx      # Çalışma saatleri
│           ├── subscription/
│           │   └── page.tsx      # Plan & abonelik
│           └── team/
│               └── page.tsx      # Ekip yönetimi
│
├── (client)/                     # CLIENT paneli
│   ├── layout.tsx                # Client layout
│   └── portal/
│       ├── page.tsx              # Client dashboard
│       ├── appointments/
│       │   └── page.tsx          # Randevularım
│       ├── reviews/
│       │   └── page.tsx          # Değerlendirmelerim
│       └── profile/
│           └── page.tsx          # Profil ayarları
│
├── (staff)/                      # STAFF paneli (read-only kısıtlı)
│   ├── layout.tsx                # Staff layout
│   └── staff/
│       ├── page.tsx              # Staff dashboard
│       ├── calendar/
│       │   └── page.tsx          # Takvimim
│       ├── schedule/
│       │   └── page.tsx          # Çalışma saatlerim
│       └── appointments/
│           └── [id]/
│               └── page.tsx      # Randevu detay (sadece kendi)
│
└── (platform)/                   # PLATFORM_ADMIN (ayrı deploy olabilir)
    ├── layout.tsx                # Platform layout
    └── platform/
        ├── page.tsx              # Platform dashboard
        ├── tenants/
        │   └── page.tsx          # Tenant yönetimi
        └── subscriptions/
            └── page.tsx          # Abonelik yönetimi
```

> **Backend referans:** Route grupları backend API scope'ları ile eşleşir:
> - `(public)` → `/api/public/**` (permitAll)
> - `(auth)` → `/api/auth/**` (permitAll)
> - `(admin)` → `/api/admin/**` (TENANT_ADMIN)
> - `(client)` → `/api/client/**` (CLIENT)
> - `(staff)` → `/api/staff/**` (STAFF)
> - `(platform)` → `/api/platform/**` (PLATFORM_ADMIN)

### 3.3 `src/features/` — Feature-First Modüller

```
src/features/
├── appointments/
│   ├── api/                      # TanStack Query hooks
│   │   ├── use-appointments.ts
│   │   ├── use-create-appointment.ts
│   │   ├── use-update-appointment-status.ts
│   │   └── use-appointment-calendar.ts
│   ├── components/               # Feature-specific components
│   │   ├── appointment-form.tsx
│   │   ├── appointment-card.tsx
│   │   ├── appointment-calendar.tsx
│   │   ├── appointment-status-badge.tsx
│   │   ├── slot-picker.tsx
│   │   └── reschedule-dialog.tsx
│   ├── hooks/                    # Feature-specific hooks
│   │   └── use-appointment-status-machine.ts
│   ├── types/                    # Feature-specific types
│   │   └── index.ts
│   └── lib/                      # Feature-specific utilities
│       ├── status-transitions.ts
│       └── time-slot-utils.ts
│
├── auth/
│   ├── api/
│   │   ├── use-login.ts
│   │   ├── use-register.ts
│   │   ├── use-refresh-token.ts
│   │   └── use-forgot-password.ts
│   ├── components/
│   │   ├── login-form.tsx
│   │   ├── register-form.tsx
│   │   └── forgot-password-form.tsx
│   └── types/
│       └── index.ts
│
├── services/
│   ├── api/
│   ├── components/
│   └── types/
│
├── staff/
│   ├── api/
│   ├── components/
│   └── types/
│
├── clients/
│   ├── api/
│   ├── components/
│   └── types/
│
├── blog/
│   ├── api/
│   ├── components/
│   └── types/
│
├── products/
│   ├── api/
│   ├── components/
│   └── types/
│
├── gallery/
│   ├── api/
│   ├── components/
│   └── types/
│
├── reviews/
│   ├── api/
│   ├── components/
│   └── types/
│
├── patient-records/
│   ├── api/
│   ├── components/
│   └── types/
│
├── notifications/
│   ├── api/
│   ├── components/
│   └── types/
│
├── dashboard/
│   ├── api/
│   ├── components/
│   └── types/
│
├── settings/
│   ├── api/
│   ├── components/
│   └── types/
│
└── subscription/
    ├── api/
    ├── components/
    └── types/
```

### 3.4 `src/components/` — Paylaşılan Bileşenler

```
src/components/
├── ui/                           # shadcn/ui bileşenleri (otomatik generate)
│   ├── button.tsx
│   ├── input.tsx
│   ├── dialog.tsx
│   ├── dropdown-menu.tsx
│   ├── select.tsx
│   ├── table.tsx
│   ├── card.tsx
│   ├── badge.tsx
│   ├── calendar.tsx
│   ├── toast.tsx                 # Sonner wrapper
│   └── ...
│
├── layout/                       # Layout bileşenleri
│   ├── admin-sidebar.tsx
│   ├── admin-header.tsx
│   ├── public-navbar.tsx
│   ├── public-footer.tsx
│   ├── client-sidebar.tsx
│   └── staff-sidebar.tsx
│
├── shared/                       # Paylaşılan feature-agnostic bileşenler
│   ├── data-table.tsx            # TanStack Table wrapper
│   ├── data-table-pagination.tsx
│   ├── data-table-toolbar.tsx
│   ├── loading-spinner.tsx
│   ├── empty-state.tsx
│   ├── error-boundary.tsx
│   ├── confirm-dialog.tsx
│   ├── file-upload.tsx           # react-dropzone wrapper
│   ├── image-crop-dialog.tsx     # react-image-crop wrapper
│   ├── rich-text-editor.tsx      # Tiptap wrapper
│   ├── rich-text-viewer.tsx      # Tiptap read-only
│   ├── page-header.tsx
│   └── stat-card.tsx
│
└── guards/                       # Erişim kontrol bileşenleri
    ├── auth-guard.tsx            # Giriş zorunlu
    ├── role-guard.tsx            # Rol bazlı erişim
    ├── module-guard.tsx          # Modül bazlı erişim
    └── tenant-guard.tsx          # Tenant aktif kontrolü
```

### 3.5 `src/lib/` — Utility & Config

```
src/lib/
├── api/
│   ├── client.ts                 # Axios instance + interceptors
│   ├── endpoints.ts              # API endpoint sabitleri
│   └── types.ts                  # ApiResponse, PagedResponse, ErrorResponse
├── providers/
│   ├── app-providers.tsx         # Tüm provider'ları birleştir
│   ├── query-provider.tsx        # TanStack Query provider
│   ├── tenant-provider.tsx       # Tenant context
│   ├── auth-provider.tsx         # Auth context
│   ├── theme-provider.tsx        # next-themes + tenant tema
│   └── terminology-provider.tsx  # BusinessType terminoloji
├── hooks/
│   ├── use-auth.ts               # Auth state & actions
│   ├── use-tenant.ts             # Tenant context hook
│   ├── use-terminology.ts        # Terim çevirisi
│   ├── use-module-access.ts      # Modül erişim kontrolü
│   ├── use-debounce.ts           # Debounce utility
│   └── use-media-query.ts        # Responsive breakpoint
├── utils/
│   ├── cn.ts                     # clsx + tailwind-merge
│   ├── date.ts                   # date-fns wrapper (tenant timezone)
│   ├── format.ts                 # Para, telefon, tarih formatları
│   ├── validation.ts             # Ortak Zod şemaları
│   └── seo.ts                    # SEO helper fonksiyonları
├── constants/
│   ├── enums.ts                  # Backend enum karşılıkları
│   ├── routes.ts                 # Route sabitleri
│   └── query-keys.ts             # TanStack Query key factory
├── i18n/
│   ├── config.ts                 # Locale listesi, default locale
│   ├── request.ts                # next-intl request config
│   └── navigation.ts             # Localized navigation
└── store/
    ├── sidebar-store.ts          # Sidebar open/close state
    ├── booking-store.ts          # Booking wizard state
    └── notification-store.ts     # Unread count
```

---

## 4. Auth & Token Yönetimi

### 4.1 HttpOnly Cookie JWT Akışı

Backend Spring Boot, JWT token'ları **HttpOnly Cookie** olarak set eder. Bu yaklaşım XSS saldırılarına karşı koruma sağlar — JavaScript token'a erişemez.

```
Login Akışı:
  ┌──────────┐                    ┌──────────────┐
  │ Frontend  │                    │   Backend    │
  └─────┬────┘                    └──────┬───────┘
        │  POST /api/auth/login           │
        │  { email, password }            │
        ├────────────────────────────────►│
        │                                 │ Doğrula + JWT oluştur
        │  Set-Cookie: access_token=xxx;  │
        │  HttpOnly; Secure; SameSite=Lax │
        │  Set-Cookie: refresh_token=yyy; │
        │  HttpOnly; Secure; SameSite=Lax;│
        │  Path=/api/auth/refresh         │
        │◄────────────────────────────────┤
        │                                 │
        │  Response: { user, role }       │
        │◄────────────────────────────────┤
        │                                 │
  ┌─────▼────┐                    ┌──────▼───────┐
  │ AuthCtx   │                    │              │
  │ set user  │                    │              │
  └──────────┘                    └──────────────┘
```

```
Token Refresh Akışı:
  ┌──────────┐                    ┌──────────────┐
  │ Frontend  │                    │   Backend    │
  └─────┬────┘                    └──────┬───────┘
        │  API isteği (access_token       │
        │  cookie otomatik gönderilir)    │
        ├────────────────────────────────►│
        │                                 │ 401 Unauthorized
        │◄────────────────────────────────┤ (token expired)
        │                                 │
        │  POST /api/auth/refresh         │
        │  (refresh_token cookie          │
        │   otomatik gönderilir)          │
        ├────────────────────────────────►│
        │                                 │ Yeni access + refresh token
        │  Set-Cookie: access_token=new   │ (rotation — eski revoke)
        │  Set-Cookie: refresh_token=new  │
        │◄────────────────────────────────┤
        │                                 │
        │  Orijinal isteği tekrarla       │
        ├────────────────────────────────►│
        │                                 │
  ┌─────▼────┐                    ┌──────▼───────┐
  │ Başarılı  │                    │              │
  └──────────┘                    └──────────────┘
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §7.2` — Token rotation & theft detection. `CLAUDE.md §9.4` — Family-based theft detection.

### 4.2 Auth Provider

```typescript
// lib/providers/auth-provider.tsx
"use client";

import {
  createContext,
  useContext,
  useState,
  useCallback,
  useEffect,
  type ReactNode,
} from "react";
import { useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";
import type { User, Role, LoginRequest, RegisterRequest } from "@/types/auth";

interface AuthContextValue {
  user: User | null;
  role: Role | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const router = useRouter();
  const queryClient = useQueryClient();

  // Mevcut kullanıcıyı /api/auth/me endpoint'inden al
  const {
    data: user,
    isLoading,
    error,
  } = useQuery({
    queryKey: ["auth", "me"],
    queryFn: () => apiClient.get<User>("/api/auth/me"),
    retry: false,
    staleTime: 5 * 60 * 1000, // 5 dk
  });

  const loginMutation = useMutation({
    mutationFn: (data: LoginRequest) =>
      apiClient.post<{ user: User }>("/api/auth/login", data),
    onSuccess: (data) => {
      queryClient.setQueryData(["auth", "me"], data.user);
      // Role'e göre yönlendir
      redirectByRole(data.user.role);
    },
  });

  const registerMutation = useMutation({
    mutationFn: (data: RegisterRequest) =>
      apiClient.post<{ user: User }>("/api/auth/register", data),
    onSuccess: () => {
      // Email doğrulama sayfasına yönlendir
      router.push("/verify-email");
    },
  });

  const logoutMutation = useMutation({
    mutationFn: () => apiClient.post("/api/auth/logout", {}),
    onSuccess: () => {
      queryClient.clear();
      router.push("/login");
    },
  });

  const redirectByRole = useCallback(
    (role: Role) => {
      switch (role) {
        case "TENANT_ADMIN":
          router.push("/admin");
          break;
        case "STAFF":
          router.push("/staff");
          break;
        case "CLIENT":
          router.push("/portal");
          break;
        case "PLATFORM_ADMIN":
          router.push("/platform");
          break;
      }
    },
    [router]
  );

  const value: AuthContextValue = {
    user: user ?? null,
    role: user?.role ?? null,
    isAuthenticated: !!user && !error,
    isLoading,
    login: loginMutation.mutateAsync,
    register: registerMutation.mutateAsync,
    logout: logoutMutation.mutateAsync,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return context;
}
```

### 4.3 Auth Tipleri

```typescript
// types/auth.ts
export type Role = "PLATFORM_ADMIN" | "TENANT_ADMIN" | "STAFF" | "CLIENT";

export interface User {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  phone: string;
  role: Role;
  isEmailVerified: boolean;
  profileImageUrl: string | null;
  createdAt: string; // ISO 8601
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  firstName: string;
  lastName: string;
  phone: string;
}

export interface ForgotPasswordRequest {
  email: string;
}

export interface ResetPasswordRequest {
  token: string;
  newPassword: string;
}
```

### 4.4 Role-Based Route Guard

```typescript
// components/guards/auth-guard.tsx
"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter, usePathname } from "next/navigation";
import { useAuth } from "@/lib/providers/auth-provider";
import { LoadingSpinner } from "@/components/shared/loading-spinner";
import type { Role } from "@/types/auth";

interface AuthGuardProps {
  children: ReactNode;
  allowedRoles?: Role[];
  redirectTo?: string;
}

export function AuthGuard({
  children,
  allowedRoles,
  redirectTo = "/login",
}: AuthGuardProps) {
  const { user, isAuthenticated, isLoading } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (isLoading) return;

    if (!isAuthenticated) {
      // Giriş sonrası geri dönmek için mevcut URL'i sakla
      const returnUrl = encodeURIComponent(pathname);
      router.replace(`${redirectTo}?returnUrl=${returnUrl}`);
      return;
    }

    if (allowedRoles && user && !allowedRoles.includes(user.role)) {
      router.replace("/unauthorized");
    }
  }, [isAuthenticated, isLoading, user, allowedRoles, router, pathname, redirectTo]);

  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (!isAuthenticated) return null;
  if (allowedRoles && user && !allowedRoles.includes(user.role)) return null;

  return <>{children}</>;
}
```

### 4.5 Admin Layout'ta Kullanım

```typescript
// app/(admin)/layout.tsx
import { AuthGuard } from "@/components/guards/auth-guard";
import { AdminSidebar } from "@/components/layout/admin-sidebar";
import { AdminHeader } from "@/components/layout/admin-header";

export default function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <AuthGuard allowedRoles={["TENANT_ADMIN"]}>
      <div className="flex h-screen">
        <AdminSidebar />
        <div className="flex flex-1 flex-col overflow-hidden">
          <AdminHeader />
          <main className="flex-1 overflow-y-auto p-6">{children}</main>
        </div>
      </div>
    </AuthGuard>
  );
}
```

---

## 5. API Client Mimarisi

### 5.1 Axios Instance & Interceptor'lar

```typescript
// lib/api/client.ts
import axios, {
  type AxiosInstance,
  type AxiosError,
  type InternalAxiosRequestConfig,
  type AxiosResponse,
} from "axios";
import { v4 as uuidv4 } from "uuid";
import type { ApiResponse, ErrorResponse } from "@/lib/api/types";

// Axios instance — HttpOnly cookie otomatik gönderilir
const axiosInstance: AxiosInstance = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL, // "https://api.app.com" veya relative "/"
  withCredentials: true, // Cookie'leri gönder (KRİTİK)
  timeout: 30_000, // 30 saniye
  headers: {
    "Content-Type": "application/json",
    Accept: "application/json",
  },
});

// ─── REQUEST INTERCEPTOR ───────────────────────────────────────────

axiosInstance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // X-Correlation-ID — Backend CorrelationIdFilter ile eşleşir (§25.2)
    // Her request'e benzersiz ID ekle, hata izleme için
    config.headers["X-Correlation-ID"] = uuidv4();

    // Accept-Language — i18n
    const locale =
      typeof window !== "undefined"
        ? document.documentElement.lang || "tr"
        : "tr";
    config.headers["Accept-Language"] = locale;

    return config;
  },
  (error) => Promise.reject(error)
);

// ─── RESPONSE INTERCEPTOR ──────────────────────────────────────────

let isRefreshing = false;
let failedQueue: Array<{
  resolve: (value?: unknown) => void;
  reject: (reason?: unknown) => void;
}> = [];

const processQueue = (error: unknown) => {
  failedQueue.forEach((prom) => {
    if (error) {
      prom.reject(error);
    } else {
      prom.resolve();
    }
  });
  failedQueue = [];
};

axiosInstance.interceptors.response.use(
  (response: AxiosResponse) => response,
  async (error: AxiosError<ErrorResponse>) => {
    const originalRequest = error.config as InternalAxiosRequestConfig & {
      _retry?: boolean;
    };

    // 401 — Token expired, refresh dene
    if (error.response?.status === 401 && !originalRequest._retry) {
      if (isRefreshing) {
        // Zaten refresh yapılıyor — kuyruğa ekle
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        }).then(() => axiosInstance(originalRequest));
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        // Refresh token da HttpOnly cookie — otomatik gönderilir
        await axios.post(
          `${process.env.NEXT_PUBLIC_API_URL}/api/auth/refresh`,
          {},
          { withCredentials: true }
        );

        processQueue(null);
        return axiosInstance(originalRequest);
      } catch (refreshError) {
        processQueue(refreshError);
        // Refresh da başarısız — logout
        if (typeof window !== "undefined") {
          window.location.href = "/login?session=expired";
        }
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  }
);

// ─── API CLIENT WRAPPER ────────────────────────────────────────────

export const apiClient = {
  async get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
    const response = await axiosInstance.get<ApiResponse<T>>(url, { params });
    return response.data.data as T;
  },

  async post<T>(url: string, data: unknown): Promise<T> {
    const response = await axiosInstance.post<ApiResponse<T>>(url, data);
    return response.data.data as T;
  },

  async put<T>(url: string, data: unknown): Promise<T> {
    const response = await axiosInstance.put<ApiResponse<T>>(url, data);
    return response.data.data as T;
  },

  async patch<T>(url: string, data: unknown): Promise<T> {
    const response = await axiosInstance.patch<ApiResponse<T>>(url, data);
    return response.data.data as T;
  },

  async delete(url: string): Promise<void> {
    await axiosInstance.delete(url);
  },

  async upload<T>(url: string, formData: FormData): Promise<T> {
    const response = await axiosInstance.post<ApiResponse<T>>(url, formData, {
      headers: { "Content-Type": "multipart/form-data" },
      timeout: 120_000, // Dosya yükleme için 2 dk
    });
    return response.data.data as T;
  },

  // Sayfalı veri çekme
  async getPaged<T>(
    url: string,
    params?: Record<string, unknown>
  ): Promise<PagedData<T>> {
    const response = await axiosInstance.get<PagedResponse<T>>(url, { params });
    const { data: items, page, size, totalElements, totalPages } = response.data;
    return { items: items ?? [], page, size, totalElements, totalPages };
  },
};
```

### 5.2 API Response Tipleri

```typescript
// lib/api/types.ts

// Backend ApiResponse<T> karşılığı (BACKEND_ARCHITECTURE.md §6.5)
export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  message: string | null;
  timestamp: string; // ISO 8601
}

// Backend PagedResponse<T> karşılığı
export interface PagedResponse<T> {
  success: boolean;
  data: T[] | null;
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  message: string | null;
  timestamp: string;
}

// Backend ErrorResponse karşılığı
export interface ErrorResponse {
  success: false;
  error: string;
  code: ErrorCode;
  details: Record<string, string> | null;
  timestamp: string;
}

// Frontend internal — sayfalı veri
export interface PagedData<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// Backend ErrorCode enum karşılığı (birebir eşleşme)
export type ErrorCode =
  | "VALIDATION_ERROR"
  | "INVALID_CREDENTIALS"
  | "FORBIDDEN"
  | "PLAN_LIMIT_EXCEEDED"
  | "CLIENT_BLACKLISTED"
  | "RESOURCE_NOT_FOUND"
  | "TENANT_NOT_FOUND"
  | "APPOINTMENT_CONFLICT"
  | "DUPLICATE_RESOURCE"
  | "ACCOUNT_LOCKED"
  | "APPOINTMENT_INVALID_STATUS"
  | "APPOINTMENT_PAST_DATE"
  | "NO_AVAILABLE_STAFF"
  | "NOTIFICATION_DELIVERY_FAILED"
  | "RATE_LIMIT_EXCEEDED"
  | "INTERNAL_ERROR";
```

### 5.3 Error Code → Kullanıcı Mesajı Eşleştirmesi

```typescript
// lib/api/error-messages.ts
import type { ErrorCode } from "@/lib/api/types";

// Backend ErrorCode → Kullanıcı dostu mesaj
// Backend referans: CLAUDE.md §8 — Exception → HTTP Status Mapping
export const ERROR_MESSAGES: Record<ErrorCode, string> = {
  VALIDATION_ERROR: "Lütfen formu kontrol edin.",
  INVALID_CREDENTIALS: "E-posta veya şifre hatalı.",
  FORBIDDEN: "Bu işlem için yetkiniz yok.",
  PLAN_LIMIT_EXCEEDED:
    "Plan limitinize ulaştınız. Planınızı yükseltin.",
  CLIENT_BLACKLISTED:
    "Hesabınız kara listeye alınmıştır. Lütfen işletmeyle iletişime geçin.",
  RESOURCE_NOT_FOUND: "Aradığınız kayıt bulunamadı.",
  TENANT_NOT_FOUND: "İşletme bulunamadı veya aktif değil.",
  APPOINTMENT_CONFLICT: "Seçilen zaman diliminde çakışma var.",
  DUPLICATE_RESOURCE: "Bu kayıt zaten mevcut.",
  ACCOUNT_LOCKED:
    "Hesabınız çok fazla başarısız giriş nedeniyle kilitlendi. 15 dakika sonra tekrar deneyin.",
  APPOINTMENT_INVALID_STATUS: "Randevu durumu bu geçişe izin vermiyor.",
  APPOINTMENT_PAST_DATE: "Geçmiş tarihli randevu oluşturulamaz.",
  NO_AVAILABLE_STAFF: "Seçilen zaman diliminde uygun personel bulunamadı.",
  NOTIFICATION_DELIVERY_FAILED: "Bildirim gönderilemedi.",
  RATE_LIMIT_EXCEEDED: "Çok fazla istek gönderildi. Lütfen bir süre bekleyin.",
  INTERNAL_ERROR: "Beklenmeyen bir hata oluştu. Lütfen tekrar deneyin.",
};

/**
 * API hatasından kullanıcı dostu mesaj çıkar.
 * Validation hatalarında field-level detail döner.
 */
export function getErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error) && error.response?.data) {
    const errorData = error.response.data as ErrorResponse;
    const code = errorData.code;

    // Validation hataları — field-level mesajlar
    if (code === "VALIDATION_ERROR" && errorData.details) {
      return Object.values(errorData.details).join(". ");
    }

    return ERROR_MESSAGES[code] || errorData.error || "Bir hata oluştu.";
  }

  if (error instanceof Error) {
    return error.message;
  }

  return "Beklenmeyen bir hata oluştu.";
}
```

### 5.4 X-Correlation-ID Propagation

Her API isteğine benzersiz bir `X-Correlation-ID` header'ı eklenir. Bu ID backend'deki `CorrelationIdFilter` tarafından okunur ve tüm log'lara eklenir. Hata durumunda Sentry'ye de gönderilir.

```typescript
// lib/api/correlation.ts
import * as Sentry from "@sentry/nextjs";

/**
 * API hatası Sentry'ye raporlanırken Correlation ID eklenir.
 * Bu sayede backend log'ları ile frontend hata raporu eşleştirilebilir.
 */
export function reportApiError(error: AxiosError, correlationId: string) {
  Sentry.withScope((scope) => {
    scope.setTag("correlation_id", correlationId);
    scope.setExtra("api_url", error.config?.url);
    scope.setExtra("api_method", error.config?.method);
    scope.setExtra("status_code", error.response?.status);
    Sentry.captureException(error);
  });
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §25.2` — CorrelationIdFilter, MDC ile tüm log'lara correlation ID ekler.

---

## 6. Data Fetching (TanStack Query)

### 6.1 QueryClient Konfigürasyonu

```typescript
// lib/providers/query-provider.tsx
"use client";

import { useState, type ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactQueryDevtools } from "@tanstack/react-query-devtools";

function makeQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Veri ne kadar süre "taze" kabul edilir
        staleTime: 60 * 1000, // 1 dk default
        // Cache'te ne kadar süre tutulur
        gcTime: 5 * 60 * 1000, // 5 dk
        // Pencere odağa geldiğinde refetch
        refetchOnWindowFocus: true,
        // Network tekrar bağlandığında refetch
        refetchOnReconnect: true,
        // 401 hariç 3 kez dene
        retry: (failureCount, error) => {
          // 401, 403, 404 retry yapma
          if (error instanceof Error && "status" in error) {
            const status = (error as { status: number }).status;
            if ([401, 403, 404].includes(status)) return false;
          }
          return failureCount < 3;
        },
      },
      mutations: {
        // Mutation hataları toast ile göster
        onError: (error) => {
          const message = getErrorMessage(error);
          toast.error(message);
        },
      },
    },
  });
}

export function QueryProvider({ children }: { children: ReactNode }) {
  const [queryClient] = useState(makeQueryClient);

  return (
    <QueryClientProvider client={queryClient}>
      {children}
      {process.env.NODE_ENV === "development" && (
        <ReactQueryDevtools initialIsOpen={false} />
      )}
    </QueryClientProvider>
  );
}
```

### 6.2 Query Key Convention

Query key'ler tutarlı bir hiyerarşi ile organize edilir. Bu, cache invalidation'ı kolaylaştırır.

```typescript
// lib/constants/query-keys.ts

// Query key factory pattern
// Hiyerarşi: [scope, entity, ...params]
export const queryKeys = {
  // ─── Auth ────────────────────────────────────
  auth: {
    me: () => ["auth", "me"] as const,
  },

  // ─── Tenant ──────────────────────────────────
  tenant: {
    config: (slug: string) => ["tenant", "config", slug] as const,
  },

  // ─── Appointments ────────────────────────────
  appointments: {
    all: () => ["appointments"] as const,
    list: (params: AppointmentListParams) =>
      ["appointments", "list", params] as const,
    detail: (id: string) => ["appointments", "detail", id] as const,
    calendar: (date: string, staffId?: string) =>
      ["appointments", "calendar", { date, staffId }] as const,
    stats: (date: string) => ["appointments", "stats", date] as const,
  },

  // ─── Services ────────────────────────────────
  services: {
    all: () => ["services"] as const,
    list: (params?: ServiceListParams) =>
      ["services", "list", params] as const,
    detail: (id: string) => ["services", "detail", id] as const,
    bySlug: (slug: string) => ["services", "slug", slug] as const,
    categories: () => ["services", "categories"] as const,
  },

  // ─── Staff ───────────────────────────────────
  staff: {
    all: () => ["staff"] as const,
    list: (params?: StaffListParams) => ["staff", "list", params] as const,
    detail: (id: string) => ["staff", "detail", id] as const,
    availability: (staffId: string, date: string) =>
      ["staff", "availability", staffId, date] as const,
    byService: (serviceId: string) =>
      ["staff", "by-service", serviceId] as const,
  },

  // ─── Clients ─────────────────────────────────
  clients: {
    all: () => ["clients"] as const,
    list: (params?: ClientListParams) =>
      ["clients", "list", params] as const,
    detail: (id: string) => ["clients", "detail", id] as const,
    appointments: (clientId: string) =>
      ["clients", "appointments", clientId] as const,
  },

  // ─── Blog ────────────────────────────────────
  blog: {
    all: () => ["blog"] as const,
    list: (params?: BlogListParams) => ["blog", "list", params] as const,
    detail: (id: string) => ["blog", "detail", id] as const,
    bySlug: (slug: string) => ["blog", "slug", slug] as const,
  },

  // ─── Products ────────────────────────────────
  products: {
    all: () => ["products"] as const,
    list: (params?: ProductListParams) =>
      ["products", "list", params] as const,
    detail: (id: string) => ["products", "detail", id] as const,
  },

  // ─── Reviews ─────────────────────────────────
  reviews: {
    all: () => ["reviews"] as const,
    list: (params?: ReviewListParams) =>
      ["reviews", "list", params] as const,
    stats: () => ["reviews", "stats"] as const,
  },

  // ─── Gallery ─────────────────────────────────
  gallery: {
    all: () => ["gallery"] as const,
    list: (params?: GalleryListParams) =>
      ["gallery", "list", params] as const,
  },

  // ─── Dashboard ───────────────────────────────
  dashboard: {
    stats: (date: string) => ["dashboard", "stats", date] as const,
    weekly: () => ["dashboard", "weekly"] as const,
  },

  // ─── Notifications ───────────────────────────
  notifications: {
    all: () => ["notifications"] as const,
    list: (params?: NotificationListParams) =>
      ["notifications", "list", params] as const,
    unreadCount: () => ["notifications", "unread-count"] as const,
    templates: () => ["notifications", "templates"] as const,
  },

  // ─── Patient Records ────────────────────────
  patientRecords: {
    all: () => ["patient-records"] as const,
    byClient: (clientId: string) =>
      ["patient-records", "client", clientId] as const,
    detail: (id: string) => ["patient-records", "detail", id] as const,
  },

  // ─── Settings ────────────────────────────────
  settings: {
    site: () => ["settings", "site"] as const,
    workingHours: () => ["settings", "working-hours"] as const,
    blockedSlots: (params?: BlockedSlotParams) =>
      ["settings", "blocked-slots", params] as const,
  },

  // ─── Subscription ───────────────────────────
  subscription: {
    current: () => ["subscription", "current"] as const,
    plans: () => ["subscription", "plans"] as const,
    invoices: () => ["subscription", "invoices"] as const,
  },

  // ─── Client Portal ──────────────────────────
  portal: {
    myAppointments: (params?: PortalAppointmentParams) =>
      ["portal", "my-appointments", params] as const,
    myReviews: () => ["portal", "my-reviews"] as const,
    profile: () => ["portal", "profile"] as const,
  },

  // ─── Availability (PUBLIC — CACHE YOK) ──────
  availability: {
    slots: (date: string, serviceIds: string[]) =>
      ["availability", "slots", date, serviceIds] as const,
  },
} as const;
```

### 6.3 Query Hook Örnekleri

```typescript
// features/appointments/api/use-appointments.ts
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";
import { queryKeys } from "@/lib/constants/query-keys";
import type { AppointmentResponse, AppointmentListParams } from "../types";
import type { PagedData } from "@/lib/api/types";

export function useAppointments(params: AppointmentListParams) {
  return useQuery({
    queryKey: queryKeys.appointments.list(params),
    queryFn: () =>
      apiClient.getPaged<AppointmentResponse>("/api/admin/appointments", {
        page: params.page,
        size: params.size,
        status: params.status,
        date: params.date,
        staffId: params.staffId,
        sort: params.sort,
      }),
    // Liste verisi biraz daha kısa taze kabul edilir
    staleTime: 30 * 1000, // 30 sn
  });
}

export function useAppointmentDetail(id: string) {
  return useQuery({
    queryKey: queryKeys.appointments.detail(id),
    queryFn: () =>
      apiClient.get<AppointmentResponse>(`/api/admin/appointments/${id}`),
    enabled: !!id, // id yoksa çalışma
  });
}
```

### 6.4 Mutation + Optimistic Update

```typescript
// features/appointments/api/use-update-appointment-status.ts
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { apiClient } from "@/lib/api/client";
import { queryKeys } from "@/lib/constants/query-keys";
import { getErrorMessage } from "@/lib/api/error-messages";
import type { AppointmentResponse, AppointmentStatus } from "../types";

interface UpdateStatusRequest {
  appointmentId: string;
  status: AppointmentStatus;
}

export function useUpdateAppointmentStatus() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ appointmentId, status }: UpdateStatusRequest) =>
      apiClient.patch<AppointmentResponse>(
        `/api/admin/appointments/${appointmentId}/status`,
        { status }
      ),

    // Optimistic update — UI anında güncellenir
    onMutate: async ({ appointmentId, status }) => {
      // Mevcut cache'i iptal et (refetch'i önle)
      await queryClient.cancelQueries({
        queryKey: queryKeys.appointments.detail(appointmentId),
      });

      // Önceki veriyi sakla (rollback için)
      const previousData = queryClient.getQueryData<AppointmentResponse>(
        queryKeys.appointments.detail(appointmentId)
      );

      // Cache'i iyimser güncelle
      if (previousData) {
        queryClient.setQueryData(
          queryKeys.appointments.detail(appointmentId),
          { ...previousData, status }
        );
      }

      return { previousData };
    },

    // Hata → rollback
    onError: (error, { appointmentId }, context) => {
      if (context?.previousData) {
        queryClient.setQueryData(
          queryKeys.appointments.detail(appointmentId),
          context.previousData
        );
      }
      toast.error(getErrorMessage(error));
    },

    // Başarılı veya hatalı → ilgili cache'leri yenile
    onSettled: (_, __, { appointmentId }) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.appointments.all(),
      });
      queryClient.invalidateQueries({
        queryKey: queryKeys.dashboard.stats(new Date().toISOString().split("T")[0]),
      });
    },

    onSuccess: () => {
      toast.success("Randevu durumu güncellendi.");
    },
  });
}
```

### 6.5 Cache Invalidation Stratejisi

| Aksiyon | Invalidate Edilecek Key'ler |
|---------|---------------------------|
| Randevu oluştur | `appointments.all`, `dashboard.stats`, `availability.slots` |
| Randevu durumu güncelle | `appointments.all`, `appointments.detail(id)`, `dashboard.stats` |
| Randevu iptal | `appointments.all`, `dashboard.stats`, `availability.slots` |
| Hizmet oluştur/güncelle | `services.all`, `services.categories` |
| Personel oluştur/güncelle | `staff.all`, `staff.byService(*)` |
| Blog yayınla | `blog.all` |
| Ayar güncelle | `settings.site`, `tenant.config` |
| Plan değiştir | `subscription.current`, `subscription.plans` |

### 6.6 Prefetching — Sayfa Geçişlerinde

```typescript
// features/appointments/components/appointment-list.tsx
"use client";

import Link from "next/link";
import { useQueryClient } from "@tanstack/react-query";
import { queryKeys } from "@/lib/constants/query-keys";
import { apiClient } from "@/lib/api/client";

export function AppointmentRow({ appointment }: { appointment: AppointmentResponse }) {
  const queryClient = useQueryClient();

  // Mouse hover'da detay verisini önceden yükle
  const prefetchDetail = () => {
    queryClient.prefetchQuery({
      queryKey: queryKeys.appointments.detail(appointment.id),
      queryFn: () =>
        apiClient.get<AppointmentResponse>(
          `/api/admin/appointments/${appointment.id}`
        ),
      staleTime: 60 * 1000,
    });
  };

  return (
    <Link
      href={`/admin/appointments/${appointment.id}`}
      onMouseEnter={prefetchDetail}
      className="block p-4 hover:bg-muted/50 transition-colors"
    >
      {/* ... */}
    </Link>
  );
}
```

### 6.7 Availability — Cache YOK

```typescript
// features/appointments/api/use-availability.ts
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";
import { queryKeys } from "@/lib/constants/query-keys";
import type { AvailableSlot } from "../types";

/**
 * Müsait zaman dilimlerini çeker.
 * KRİTİK: staleTime = 0, gcTime = 0 — cache YAPMA!
 * Çift randevu riski nedeniyle her sorguda güncel veri gerekli.
 */
export function useAvailableSlots(date: string, serviceIds: string[]) {
  return useQuery({
    queryKey: queryKeys.availability.slots(date, serviceIds),
    queryFn: () =>
      apiClient.get<AvailableSlot[]>("/api/public/availability/slots", {
        date,
        serviceIds: serviceIds.join(","),
      }),
    enabled: !!date && serviceIds.length > 0,
    staleTime: 0, // ASLA cache'leme
    gcTime: 0,
    refetchOnWindowFocus: true,
    refetchInterval: 30 * 1000, // 30 sn'de bir yenile
  });
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §18.2` — Availability slots: **CACHE YOK** (gerçek zamanlı doğruluk şart, çift randevu riski).

---

## 7. State Management

### 7.1 State Kategorileri

Frontend'de 3 farklı state kategorisi vardır. Her biri farklı bir araçla yönetilir:

```
┌─────────────────────────────────────────────────────────┐
│                    STATE MANAGEMENT                      │
├─────────────────┬─────────────────┬─────────────────────┤
│  Server State   │  Client State   │     URL State        │
│  (TanStack Q.)  │  (Zustand)      │  (searchParams)      │
├─────────────────┼─────────────────┼─────────────────────┤
│  API'den gelen  │  UI durumu      │  Filtreler           │
│  veri           │  (sidebar,      │  Sayfalama           │
│                 │   modal, wizard  │  Sıralama            │
│  Randevular     │   step)         │  Arama               │
│  Hizmetler      │                 │  Tarih filtresi      │
│  Personel       │  Form draft     │  Tab seçimi          │
│  Blog yazıları  │  (kaydedilmemiş │                      │
│  Dashboard      │   değişiklik)   │                      │
│  istatistikleri │                 │                      │
├─────────────────┼─────────────────┼─────────────────────┤
│  Otomatik       │  Manuel         │  URL'de kalıcı       │
│  cache +        │  persist        │  Paylaşılabilir      │
│  invalidation   │  (opsiyonel)    │  Geri/ileri tuşu     │
└─────────────────┴─────────────────┴─────────────────────┘
```

### 7.2 Kural: Ne Nerede Tutulur?

| Veri Tipi | Araç | Gerekçe |
|-----------|------|---------|
| API'den gelen tüm veri | TanStack Query | Otomatik cache, refetch, invalidation |
| Sidebar açık/kapalı | Zustand | Sadece UI durumu, API ile ilişkisi yok |
| Booking wizard step | Zustand | Çok adımlı form, API'ye gönderilene kadar client-side |
| Unread notification count | Zustand (+ TanStack Query sync) | Hızlı badge güncelleme |
| Tablo filtreler | URL searchParams | Paylaşılabilir, bookmark'lanabilir |
| Tablo sayfalama | URL searchParams | Geri tuşuyla önceki sayfaya dönüş |
| Seçili tarih | URL searchParams | URL'de kalıcı |
| Modal açık/kapalı | React useState | Sadece o component'te |
| Form draft | React useState / React Hook Form | Henüz submit edilmemiş veri |

> **KRİTİK KURAL:** API'den gelen veriyi Zustand'da SAKLAMAK yasaktır. TanStack Query zaten cache mekanizması sağlar. Zustand'a kopyalamak **stale data** riskine yol açar.

### 7.3 Zustand Store Örnekleri

```typescript
// lib/store/sidebar-store.ts
import { create } from "zustand";
import { persist } from "zustand/middleware";

interface SidebarState {
  isOpen: boolean;
  isCollapsed: boolean;
  toggle: () => void;
  collapse: () => void;
  expand: () => void;
}

export const useSidebarStore = create<SidebarState>()(
  persist(
    (set) => ({
      isOpen: true,
      isCollapsed: false,
      toggle: () => set((state) => ({ isOpen: !state.isOpen })),
      collapse: () => set({ isCollapsed: true }),
      expand: () => set({ isCollapsed: false }),
    }),
    { name: "sidebar-state" }
  )
);
```

```typescript
// lib/store/booking-store.ts
import { create } from "zustand";

interface BookingState {
  step: number;
  selectedServiceIds: string[];
  selectedStaffId: string | null;
  selectedDate: string | null;
  selectedTime: string | null;
  customerInfo: {
    firstName: string;
    lastName: string;
    email: string;
    phone: string;
  } | null;
  reservationId: string | null; // Redis slot reservation ID
  reservationExpiresAt: number | null; // Timestamp

  // Actions
  setStep: (step: number) => void;
  selectServices: (ids: string[]) => void;
  selectStaff: (id: string | null) => void;
  selectDateTime: (date: string, time: string) => void;
  setCustomerInfo: (info: BookingState["customerInfo"]) => void;
  setReservation: (id: string, expiresAt: number) => void;
  reset: () => void;
}

const initialState = {
  step: 0,
  selectedServiceIds: [],
  selectedStaffId: null,
  selectedDate: null,
  selectedTime: null,
  customerInfo: null,
  reservationId: null,
  reservationExpiresAt: null,
};

export const useBookingStore = create<BookingState>((set) => ({
  ...initialState,
  setStep: (step) => set({ step }),
  selectServices: (ids) => set({ selectedServiceIds: ids }),
  selectStaff: (id) => set({ selectedStaffId: id }),
  selectDateTime: (date, time) =>
    set({ selectedDate: date, selectedTime: time }),
  setCustomerInfo: (info) => set({ customerInfo: info }),
  setReservation: (id, expiresAt) =>
    set({ reservationId: id, reservationExpiresAt: expiresAt }),
  reset: () => set(initialState),
}));
```

### 7.4 URL State — searchParams ile Filtreleme

```typescript
// features/appointments/hooks/use-appointment-filters.ts
import { useSearchParams, useRouter, usePathname } from "next/navigation";
import { useCallback, useMemo } from "react";
import type { AppointmentStatus } from "../types";

export function useAppointmentFilters() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const filters = useMemo(
    () => ({
      page: Number(searchParams.get("page")) || 0,
      size: Number(searchParams.get("size")) || 20,
      status: (searchParams.get("status") as AppointmentStatus) || undefined,
      date: searchParams.get("date") || undefined,
      staffId: searchParams.get("staffId") || undefined,
      search: searchParams.get("search") || undefined,
      sort: searchParams.get("sort") || "date,desc",
    }),
    [searchParams]
  );

  const setFilter = useCallback(
    (key: string, value: string | null) => {
      const params = new URLSearchParams(searchParams.toString());
      if (value === null || value === "") {
        params.delete(key);
      } else {
        params.set(key, value);
      }
      // Filtre değiştiğinde sayfa sıfırla
      if (key !== "page") params.set("page", "0");
      router.push(`${pathname}?${params.toString()}`);
    },
    [searchParams, router, pathname]
  );

  const resetFilters = useCallback(() => {
    router.push(pathname);
  }, [router, pathname]);

  return { filters, setFilter, resetFilters };
}
```

---

## 8. Modül Sistemi

### 8.1 FeatureModule Enum

Backend'deki `FeatureModule` enum'ı ile birebir eşleşir.

```typescript
// lib/constants/enums.ts

// Backend karşılığı: com.aesthetic.backend.domain.subscription.FeatureModule
export const FeatureModule = {
  APPOINTMENTS: "APPOINTMENTS",
  PRODUCTS: "PRODUCTS",
  BLOG: "BLOG",
  GALLERY: "GALLERY",
  REVIEWS: "REVIEWS",
  CONTACT_MESSAGES: "CONTACT_MESSAGES",
  PATIENT_RECORDS: "PATIENT_RECORDS",
  NOTIFICATIONS: "NOTIFICATIONS",
  CLIENT_NOTES: "CLIENT_NOTES",
} as const;

export type FeatureModule = (typeof FeatureModule)[keyof typeof FeatureModule];
```

### 8.2 useModuleAccess Hook

```typescript
// lib/hooks/use-module-access.ts
import { useTenant } from "@/lib/providers/tenant-provider";
import type { FeatureModule } from "@/lib/constants/enums";

interface ModuleAccess {
  hasAccess: (module: FeatureModule) => boolean;
  isTrial: boolean;
  enabledModules: FeatureModule[];
}

export function useModuleAccess(): ModuleAccess {
  const { tenant } = useTenant();

  const isTrial = tenant?.plan === "TRIAL";
  const enabledModules = tenant?.enabledModules ?? [];

  const hasAccess = (module: FeatureModule): boolean => {
    // TRIAL — tüm modüller açık (backend ile aynı davranış)
    if (isTrial) return true;
    return enabledModules.includes(module);
  };

  return { hasAccess, isTrial, enabledModules };
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §17.3` — ModuleAccessService: TRIAL ise tüm modüller açık.

### 8.3 ModuleGuard Component

```typescript
// components/guards/module-guard.tsx
"use client";

import { type ReactNode } from "react";
import { useModuleAccess } from "@/lib/hooks/use-module-access";
import { useRouter } from "next/navigation";
import type { FeatureModule } from "@/lib/constants/enums";

interface ModuleGuardProps {
  children: ReactNode;
  module: FeatureModule;
  fallback?: ReactNode;
}

export function ModuleGuard({ children, module, fallback }: ModuleGuardProps) {
  const { hasAccess } = useModuleAccess();

  if (!hasAccess(module)) {
    return fallback || <ModuleUpgradePrompt module={module} />;
  }

  return <>{children}</>;
}

function ModuleUpgradePrompt({ module }: { module: FeatureModule }) {
  const router = useRouter();

  return (
    <div className="flex flex-col items-center justify-center py-16">
      <div className="rounded-lg border bg-card p-8 text-center max-w-md">
        <h2 className="text-xl font-semibold mb-2">
          Bu özellik mevcut planınızda bulunmuyor
        </h2>
        <p className="text-muted-foreground mb-4">
          {MODULE_DESCRIPTIONS[module]} özelliğini kullanmak için planınızı
          yükseltin.
        </p>
        <button
          onClick={() => router.push("/admin/settings/subscription")}
          className="inline-flex items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
        >
          Planı Yükselt
        </button>
      </div>
    </div>
  );
}

const MODULE_DESCRIPTIONS: Record<FeatureModule, string> = {
  APPOINTMENTS: "Randevu yönetimi",
  PRODUCTS: "Ürün kataloğu",
  BLOG: "Blog",
  GALLERY: "Galeri",
  REVIEWS: "Değerlendirmeler",
  CONTACT_MESSAGES: "İletişim mesajları",
  PATIENT_RECORDS: "Hasta kayıtları",
  NOTIFICATIONS: "Bildirimler",
  CLIENT_NOTES: "Müşteri notları",
};
```

### 8.4 Admin Sidebar — Dinamik Modül Navigasyonu

```typescript
// components/layout/admin-sidebar.tsx
"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  Calendar, Package, FileText, Image, Star, MessageSquare,
  ClipboardList, Bell, Settings, LayoutDashboard, Users, UserCircle,
  StickyNote,
} from "lucide-react";
import { useModuleAccess } from "@/lib/hooks/use-module-access";
import { FeatureModule } from "@/lib/constants/enums";
import { cn } from "@/lib/utils/cn";
import { useTerminology } from "@/lib/hooks/use-terminology";

interface NavItem {
  label: string;
  href: string;
  icon: React.ComponentType<{ className?: string }>;
  module?: FeatureModule; // Modül gerektirmeyen item'lar undefined
}

export function AdminSidebar() {
  const pathname = usePathname();
  const { hasAccess } = useModuleAccess();
  const t = useTerminology();

  const navItems: NavItem[] = [
    { label: "Dashboard", href: "/admin", icon: LayoutDashboard },
    { label: "Randevular", href: "/admin/appointments", icon: Calendar, module: FeatureModule.APPOINTMENTS },
    { label: "Hizmetler", href: "/admin/services", icon: Package },
    { label: "Personel", href: "/admin/staff", icon: Users },
    { label: t("clients"), href: "/admin/clients", icon: UserCircle },
    { label: "Blog", href: "/admin/blog", icon: FileText, module: FeatureModule.BLOG },
    { label: "Ürünler", href: "/admin/products", icon: Package, module: FeatureModule.PRODUCTS },
    { label: "Galeri", href: "/admin/gallery", icon: Image, module: FeatureModule.GALLERY },
    { label: "Değerlendirmeler", href: "/admin/reviews", icon: Star, module: FeatureModule.REVIEWS },
    { label: t("patientRecords"), href: "/admin/patient-records", icon: ClipboardList, module: FeatureModule.PATIENT_RECORDS },
    { label: "Bildirimler", href: "/admin/notifications", icon: Bell, module: FeatureModule.NOTIFICATIONS },
    { label: "Ayarlar", href: "/admin/settings", icon: Settings },
  ];

  // Sadece erişilebilir modülleri göster
  const visibleItems = navItems.filter(
    (item) => !item.module || hasAccess(item.module)
  );

  return (
    <aside className="flex h-full w-64 flex-col border-r bg-card">
      <nav className="flex-1 space-y-1 px-2 py-4">
        {visibleItems.map((item) => {
          const isActive = pathname === item.href || pathname.startsWith(`${item.href}/`);
          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                "flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors",
                isActive
                  ? "bg-primary/10 text-primary"
                  : "text-muted-foreground hover:bg-muted hover:text-foreground"
              )}
            >
              <item.icon className="h-4 w-4" />
              {item.label}
            </Link>
          );
        })}
      </nav>
    </aside>
  );
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §7.4` — `@RequiresModule` + `ModuleGuardInterceptor`. Frontend'deki ModuleGuard, backend'deki interceptor ile paralel çalışır.

---

## 9. Terminoloji Sistemi

### 9.1 BusinessType → Terim Eşleştirmesi

Her `BusinessType`, farklı terimler kullanır. Örneğin bir güzellik kliniği "hasta" derken, bir berber "müşteri" der.

```typescript
// lib/constants/terminology.ts
import type { BusinessType } from "@/types/tenant";

interface TerminologyMap {
  client: string;         // Müşteri/Hasta/Danışan
  clients: string;        // Müşteriler/Hastalar/Danışanlar
  appointment: string;    // Randevu/Seans/Muayene
  appointments: string;   // Randevular/Seanslar/Muayeneler
  service: string;        // Hizmet/Tedavi/İşlem
  services: string;       // Hizmetler/Tedaviler/İşlemler
  staff: string;          // Personel/Doktor/Uzman/Berber
  staffPlural: string;    // Personeller/Doktorlar/Uzmanlar
  patientRecords: string; // Hasta Kayıtları/Müşteri Kayıtları
  booking: string;        // Randevu Al/Seans Al
}

export const TERMINOLOGY: Record<BusinessType, TerminologyMap> = {
  BEAUTY_CLINIC: {
    client: "Danışan",
    clients: "Danışanlar",
    appointment: "Seans",
    appointments: "Seanslar",
    service: "İşlem",
    services: "İşlemler",
    staff: "Uzman",
    staffPlural: "Uzmanlar",
    patientRecords: "Danışan Kayıtları",
    booking: "Seans Al",
  },
  DENTAL_CLINIC: {
    client: "Hasta",
    clients: "Hastalar",
    appointment: "Muayene",
    appointments: "Muayeneler",
    service: "Tedavi",
    services: "Tedaviler",
    staff: "Doktor",
    staffPlural: "Doktorlar",
    patientRecords: "Hasta Kayıtları",
    booking: "Randevu Al",
  },
  BARBER_SHOP: {
    client: "Müşteri",
    clients: "Müşteriler",
    appointment: "Randevu",
    appointments: "Randevular",
    service: "Hizmet",
    services: "Hizmetler",
    staff: "Berber",
    staffPlural: "Berberler",
    patientRecords: "Müşteri Notları",
    booking: "Randevu Al",
  },
  HAIR_SALON: {
    client: "Müşteri",
    clients: "Müşteriler",
    appointment: "Randevu",
    appointments: "Randevular",
    service: "Hizmet",
    services: "Hizmetler",
    staff: "Kuaför",
    staffPlural: "Kuaförler",
    patientRecords: "Müşteri Kayıtları",
    booking: "Randevu Al",
  },
  DIETITIAN: {
    client: "Danışan",
    clients: "Danışanlar",
    appointment: "Görüşme",
    appointments: "Görüşmeler",
    service: "Program",
    services: "Programlar",
    staff: "Diyetisyen",
    staffPlural: "Diyetisyenler",
    patientRecords: "Danışan Kayıtları",
    booking: "Görüşme Al",
  },
  PHYSIOTHERAPIST: {
    client: "Hasta",
    clients: "Hastalar",
    appointment: "Seans",
    appointments: "Seanslar",
    service: "Tedavi",
    services: "Tedaviler",
    staff: "Fizyoterapist",
    staffPlural: "Fizyoterapistler",
    patientRecords: "Hasta Kayıtları",
    booking: "Seans Al",
  },
  MASSAGE_SALON: {
    client: "Müşteri",
    clients: "Müşteriler",
    appointment: "Seans",
    appointments: "Seanslar",
    service: "Masaj",
    services: "Masajlar",
    staff: "Terapist",
    staffPlural: "Terapistler",
    patientRecords: "Müşteri Kayıtları",
    booking: "Seans Al",
  },
  VETERINARY: {
    client: "Hasta Sahibi",
    clients: "Hasta Sahipleri",
    appointment: "Muayene",
    appointments: "Muayeneler",
    service: "Tedavi",
    services: "Tedaviler",
    staff: "Veteriner",
    staffPlural: "Veterinerler",
    patientRecords: "Hasta Kayıtları",
    booking: "Randevu Al",
  },
  GENERAL: {
    client: "Müşteri",
    clients: "Müşteriler",
    appointment: "Randevu",
    appointments: "Randevular",
    service: "Hizmet",
    services: "Hizmetler",
    staff: "Personel",
    staffPlural: "Personeller",
    patientRecords: "Müşteri Kayıtları",
    booking: "Randevu Al",
  },
};
```

### 9.2 useTerminology Hook

```typescript
// lib/hooks/use-terminology.ts
import { useCallback } from "react";
import { useTenant } from "@/lib/providers/tenant-provider";
import { TERMINOLOGY } from "@/lib/constants/terminology";
import type { BusinessType } from "@/types/tenant";

type TermKey = keyof typeof TERMINOLOGY.GENERAL;

export function useTerminology() {
  const { businessType } = useTenant();

  const t = useCallback(
    (key: TermKey): string => {
      return TERMINOLOGY[businessType]?.[key] ?? TERMINOLOGY.GENERAL[key];
    },
    [businessType]
  );

  return t;
}
```

### 9.3 Kullanım Örnekleri

```tsx
// Sayfa başlığında
function AppointmentsPage() {
  const t = useTerminology();

  return (
    <div>
      <h1 className="text-2xl font-bold">{t("appointments")}</h1>
      {/* Güzellik kliniğinde: "Seanslar" */}
      {/* Diş kliniğinde: "Muayeneler" */}
      {/* Berberde: "Randevular" */}
    </div>
  );
}

// Data table sütun başlığında
const columns = [
  {
    header: t("staff"),    // "Berber" / "Doktor" / "Uzman"
    accessorKey: "staffName",
  },
  {
    header: t("client"),   // "Müşteri" / "Hasta" / "Danışan"
    accessorKey: "clientName",
  },
];
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §4.1` — BusinessType enum (9 tip). Frontend terminoloji sistemi bu enum'a dayalıdır.

---

## 10. Tema Sistemi

### 10.1 BusinessType Preset Renkleri

Her `BusinessType` için varsayılan renk paleti tanımlıdır. Tenant kendi renklerini SiteSettings'ten override edebilir.

```typescript
// lib/constants/theme-presets.ts
import type { BusinessType } from "@/types/tenant";

interface ThemePreset {
  primary: string;
  secondary: string;
  accent: string;
}

export const THEME_PRESETS: Record<BusinessType, ThemePreset> = {
  BEAUTY_CLINIC: {
    primary: "262 80% 50%",      // Mor-pembe
    secondary: "330 80% 60%",    // Pembe
    accent: "280 70% 55%",       // Lavanta
  },
  DENTAL_CLINIC: {
    primary: "200 80% 50%",      // Mavi
    secondary: "180 60% 45%",    // Teal
    accent: "210 70% 55%",       // Açık mavi
  },
  BARBER_SHOP: {
    primary: "30 80% 50%",       // Turuncu-kahve
    secondary: "20 70% 40%",     // Kahverengi
    accent: "40 80% 55%",        // Altın
  },
  HAIR_SALON: {
    primary: "340 75% 55%",      // Pembe
    secondary: "320 60% 50%",    // Fuşya
    accent: "350 70% 60%",       // Açık pembe
  },
  DIETITIAN: {
    primary: "142 70% 45%",      // Yeşil
    secondary: "120 60% 40%",    // Koyu yeşil
    accent: "160 70% 50%",       // Mint
  },
  PHYSIOTHERAPIST: {
    primary: "200 70% 50%",      // Mavi
    secondary: "180 50% 45%",    // Teal
    accent: "220 60% 55%",       // İndigo
  },
  MASSAGE_SALON: {
    primary: "25 70% 50%",       // Sıcak turuncu
    secondary: "15 60% 45%",     // Terracotta
    accent: "35 70% 55%",        // Altın
  },
  VETERINARY: {
    primary: "142 60% 50%",      // Yeşil
    secondary: "160 50% 45%",    // Koyu yeşil
    accent: "120 70% 55%",       // Açık yeşil
  },
  GENERAL: {
    primary: "220 70% 50%",      // Mavi
    secondary: "200 60% 45%",    // Açık mavi
    accent: "240 60% 55%",       // İndigo
  },
};
```

### 10.2 ThemeProvider

```typescript
// lib/providers/theme-provider.tsx
"use client";

import { useEffect, type ReactNode } from "react";
import { ThemeProvider as NextThemesProvider } from "next-themes";
import { useTenant } from "@/lib/providers/tenant-provider";
import { THEME_PRESETS } from "@/lib/constants/theme-presets";

export function ThemeProvider({ children }: { children: ReactNode }) {
  const { tenant, businessType } = useTenant();

  useEffect(() => {
    const root = document.documentElement;

    // 1. BusinessType preset uygula
    const preset = THEME_PRESETS[businessType];
    root.style.setProperty("--primary", preset.primary);
    root.style.setProperty("--secondary", preset.secondary);
    root.style.setProperty("--accent", preset.accent);

    // 2. Tenant override (SiteSettings.themeSettings) varsa uygula
    const theme = tenant?.settings?.themeSettings;
    if (theme) {
      if (theme.primaryColor) root.style.setProperty("--primary", theme.primaryColor);
      if (theme.secondaryColor) root.style.setProperty("--secondary", theme.secondaryColor);
      if (theme.accentColor) root.style.setProperty("--accent", theme.accentColor);
      if (theme.borderRadius) root.style.setProperty("--radius", theme.borderRadius);
      if (theme.fontFamily) root.style.setProperty("--font-sans", theme.fontFamily);

      // Custom CSS (sanitize edilmiş — backend'de kontrol)
      if (theme.customCss) {
        const styleEl = document.getElementById("tenant-custom-css") ||
          document.createElement("style");
        styleEl.id = "tenant-custom-css";
        styleEl.textContent = theme.customCss;
        document.head.appendChild(styleEl);
      }
    }
  }, [tenant, businessType]);

  return (
    <NextThemesProvider
      attribute="class"
      defaultTheme="light"
      enableSystem
      disableTransitionOnChange
    >
      {children}
    </NextThemesProvider>
  );
}
```

### 10.3 Tailwind CSS v4 @theme Entegrasyonu

```css
/* styles/globals.css */
@import "tailwindcss";

@theme {
  /* Varsayılan renkler — CSS custom properties ile override edilir */
  --color-primary: hsl(var(--primary));
  --color-primary-foreground: hsl(var(--primary-foreground));
  --color-secondary: hsl(var(--secondary));
  --color-secondary-foreground: hsl(var(--secondary-foreground));
  --color-accent: hsl(var(--accent));
  --color-accent-foreground: hsl(var(--accent-foreground));
  --color-muted: hsl(var(--muted));
  --color-muted-foreground: hsl(var(--muted-foreground));
  --color-card: hsl(var(--card));
  --color-card-foreground: hsl(var(--card-foreground));
  --color-border: hsl(var(--border));
  --color-background: hsl(var(--background));
  --color-foreground: hsl(var(--foreground));
  --color-destructive: hsl(var(--destructive));

  --radius-sm: calc(var(--radius) - 4px);
  --radius-md: calc(var(--radius) - 2px);
  --radius-lg: var(--radius);
  --radius-xl: calc(var(--radius) + 4px);
}

@layer base {
  :root {
    --background: 0 0% 100%;
    --foreground: 222.2 84% 4.9%;
    --card: 0 0% 100%;
    --card-foreground: 222.2 84% 4.9%;
    --primary: 220 70% 50%;
    --primary-foreground: 210 40% 98%;
    --secondary: 210 40% 96.1%;
    --secondary-foreground: 222.2 47.4% 11.2%;
    --accent: 210 40% 96.1%;
    --accent-foreground: 222.2 47.4% 11.2%;
    --muted: 210 40% 96.1%;
    --muted-foreground: 215.4 16.3% 46.9%;
    --destructive: 0 84.2% 60.2%;
    --border: 214.3 31.8% 91.4%;
    --radius: 0.5rem;
  }

  .dark {
    --background: 222.2 84% 4.9%;
    --foreground: 210 40% 98%;
    --card: 222.2 84% 4.9%;
    --card-foreground: 210 40% 98%;
    --primary: 217.2 91.2% 59.8%;
    --primary-foreground: 222.2 47.4% 11.2%;
    --secondary: 217.2 32.6% 17.5%;
    --secondary-foreground: 210 40% 98%;
    --accent: 217.2 32.6% 17.5%;
    --accent-foreground: 210 40% 98%;
    --muted: 217.2 32.6% 17.5%;
    --muted-foreground: 215 20.2% 65.1%;
    --destructive: 0 62.8% 30.6%;
    --border: 217.2 32.6% 17.5%;
  }
}
```

### 10.4 Dark Mode Toggle

```typescript
// components/shared/theme-toggle.tsx
"use client";

import { Moon, Sun } from "lucide-react";
import { useTheme } from "next-themes";
import { Button } from "@/components/ui/button";

export function ThemeToggle() {
  const { theme, setTheme } = useTheme();

  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={() => setTheme(theme === "light" ? "dark" : "light")}
      aria-label="Tema değiştir"
    >
      <Sun className="h-4 w-4 rotate-0 scale-100 transition-all dark:-rotate-90 dark:scale-0" />
      <Moon className="absolute h-4 w-4 rotate-90 scale-0 transition-all dark:rotate-0 dark:scale-100" />
    </Button>
  );
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §4.7` — `SiteSettings.themeSettings` JSON alanı. Frontend bu JSON'ı CSS custom properties'e çevirir.

---

## 11. Form Mimarisi

### 11.1 React Hook Form + Zod Pattern

Tüm formlar React Hook Form + Zod kombinasyonu ile oluşturulur. Backend DTO validation kuralları Zod şemasına eşlenir.

```typescript
// features/services/types/index.ts
import { z } from "zod";

// Backend CreateServiceRequest DTO → Zod schema
// Backend referans: com.aesthetic.backend.dto.request.CreateServiceRequest
export const createServiceSchema = z.object({
  title: z
    .string()
    .min(1, "Hizmet adı zorunludur")
    .max(255, "Hizmet adı en fazla 255 karakter olabilir"),
  slug: z
    .string()
    .min(1, "Slug zorunludur")
    .regex(/^[a-z0-9]+(?:-[a-z0-9]+)*$/, "Slug sadece küçük harf, rakam ve tire içerebilir"),
  categoryId: z.string().nullish(),
  shortDescription: z
    .string()
    .max(500, "Kısa açıklama en fazla 500 karakter olabilir")
    .default(""),
  description: z
    .string()
    .max(10000, "Açıklama en fazla 10000 karakter olabilir")
    .default(""),
  price: z
    .number()
    .min(0, "Fiyat negatif olamaz")
    .multipleOf(0.01, "Fiyat en fazla 2 ondalık basamak olabilir"),
  currency: z.string().default("TRY"),
  durationMinutes: z
    .number()
    .int("Süre tam sayı olmalıdır")
    .min(5, "Süre en az 5 dakika olmalıdır")
    .max(480, "Süre en fazla 480 dakika olabilir"),
  bufferMinutes: z.number().int().min(0).default(0),
  image: z.string().nullish(),
  benefits: z.array(z.string()).default([]),
  processSteps: z.array(z.string()).default([]),
  recovery: z.string().nullish(),
  metaTitle: z.string().max(255).nullish(),
  metaDescription: z.string().max(500).nullish(),
});

export type CreateServiceRequest = z.infer<typeof createServiceSchema>;

// Backend UpdateServiceRequest DTO → Zod schema (partial + removeCategoryId)
export const updateServiceSchema = createServiceSchema.partial().extend({
  removeCategoryId: z.boolean().default(false),
  isActive: z.boolean().optional(),
  sortOrder: z.number().int().optional(),
});
export type UpdateServiceRequest = z.infer<typeof updateServiceSchema>;

// Backend: com.aesthetic.backend.dto.response.ServiceResponse
export interface ServiceResponse {
  id: string;
  slug: string;
  title: string;
  categoryId: string | null;
  categoryName: string | null;
  shortDescription: string;
  description: string;
  price: number; // BigDecimal → number
  currency: string;
  durationMinutes: number;
  bufferMinutes: number;
  image: string | null;
  benefits: string[];
  processSteps: string[];
  recovery: string | null;
  isActive: boolean;
  sortOrder: number;
  metaTitle: string | null;
  metaDescription: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

// Backend: com.aesthetic.backend.dto.response.ServiceCategoryResponse
export interface ServiceCategoryResponse {
  id: string;
  slug: string;
  name: string;
  description: string | null;
  image: string | null;
  sortOrder: number;
  isActive: boolean;
  serviceCount: number;
  createdAt: string | null;
  updatedAt: string | null;
}

// Backend: com.aesthetic.backend.dto.response.WorkingHoursResponse
export interface WorkingHoursResponse {
  id: string;
  dayOfWeek: DayOfWeek;
  startTime: string; // "HH:mm"
  endTime: string; // "HH:mm"
  isOpen: boolean;
}

export type DayOfWeek =
  | "MONDAY"
  | "TUESDAY"
  | "WEDNESDAY"
  | "THURSDAY"
  | "FRIDAY"
  | "SATURDAY"
  | "SUNDAY";
```

### 11.2 Form Component Pattern

```typescript
// features/services/components/service-form.tsx
"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { createServiceSchema, type CreateServiceRequest } from "../types";
import { useServiceCategories } from "../api/use-service-categories";

interface ServiceFormProps {
  defaultValues?: Partial<CreateServiceRequest>;
  onSubmit: (data: CreateServiceRequest) => void;
  isSubmitting?: boolean;
}

export function ServiceForm({
  defaultValues,
  onSubmit,
  isSubmitting,
}: ServiceFormProps) {
  const form = useForm<CreateServiceRequest>({
    resolver: zodResolver(createServiceSchema),
    defaultValues: {
      title: "",
      slug: "",
      shortDescription: "",
      description: "",
      price: 0,
      currency: "TRY",
      durationMinutes: 30,
      bufferMinutes: 0,
      categoryId: null,
      benefits: [],
      processSteps: [],
      ...defaultValues,
    },
  });

  const { data: categories } = useServiceCategories();

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
        <FormField
          control={form.control}
          name="title"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Hizmet Adı</FormLabel>
              <FormControl>
                <Input placeholder="örn: Saç Kesimi" {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="slug"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Slug (URL)</FormLabel>
              <FormControl>
                <Input placeholder="örn: sac-kesimi" {...field} />
              </FormControl>
              <FormDescription>
                URL'de görünecek benzersiz tanımlayıcı
              </FormDescription>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="description"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Açıklama</FormLabel>
              <FormControl>
                <Textarea
                  placeholder="Hizmet açıklaması..."
                  className="min-h-[100px]"
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <div className="grid grid-cols-2 gap-4">
          <FormField
            control={form.control}
            name="price"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Fiyat (TL)</FormLabel>
                <FormControl>
                  <Input
                    type="number"
                    step="0.01"
                    min="0"
                    {...field}
                    onChange={(e) => field.onChange(Number(e.target.value))}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="durationMinutes"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Süre (dk)</FormLabel>
                <FormControl>
                  <Input
                    type="number"
                    min="5"
                    step="5"
                    {...field}
                    onChange={(e) => field.onChange(Number(e.target.value))}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
        </div>

        <FormField
          control={form.control}
          name="categoryId"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Kategori</FormLabel>
              <Select onValueChange={field.onChange} defaultValue={field.value}>
                <FormControl>
                  <SelectTrigger>
                    <SelectValue placeholder="Kategori seçin" />
                  </SelectTrigger>
                </FormControl>
                <SelectContent>
                  {categories?.map((cat) => (
                    <SelectItem key={cat.id} value={cat.id}>
                      {cat.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="isActive"
          render={({ field }) => (
            <FormItem className="flex items-center justify-between rounded-lg border p-4">
              <div>
                <FormLabel>Aktif</FormLabel>
                <FormDescription>
                  Hizmeti müşterilere göster
                </FormDescription>
              </div>
              <FormControl>
                <Switch
                  checked={field.value}
                  onCheckedChange={field.onChange}
                />
              </FormControl>
            </FormItem>
          )}
        />

        <Button type="submit" disabled={isSubmitting} className="w-full">
          {isSubmitting ? "Kaydediliyor..." : "Kaydet"}
        </Button>
      </form>
    </Form>
  );
}
```

### 11.3 Randevu Form — Backend DTO Mapping

```typescript
// features/appointments/types/index.ts
import { z } from "zod";

// Backend AppointmentStatus enum karşılığı
export type AppointmentStatus =
  | "PENDING"
  | "CONFIRMED"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "CANCELLED"
  | "NO_SHOW";

// Backend CreateAppointmentRequest DTO → Zod schema
export const createAppointmentSchema = z.object({
  serviceIds: z
    .array(z.string())
    .min(1, "En az bir hizmet seçmelisiniz"),
  staffId: z.string().nullable().optional(),
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Geçerli bir tarih giriniz"),
  startTime: z.string().regex(/^\d{2}:\d{2}$/, "Geçerli bir saat giriniz"),
  clientFirstName: z.string().min(1, "Ad zorunludur"),
  clientLastName: z.string().min(1, "Soyad zorunludur"),
  clientEmail: z.string().email("Geçerli bir e-posta adresi giriniz"),
  clientPhone: z.string().min(10, "Geçerli bir telefon numarası giriniz"),
  notes: z.string().max(1000).optional().default(""),
});

export type CreateAppointmentRequest = z.infer<typeof createAppointmentSchema>;

// Backend AppointmentResponse karşılığı
export interface AppointmentResponse {
  id: string;
  date: string;          // "2025-06-15" (LocalDate)
  startTime: string;     // "14:00" (LocalTime)
  endTime: string;       // "14:30" (LocalTime)
  status: AppointmentStatus;
  clientName: string;    // Snapshot field
  clientEmail: string;   // Snapshot field
  clientPhone: string;   // Snapshot field
  staffId: string | null;
  staffName: string | null;
  services: AppointmentServiceResponse[];
  totalPrice: number;
  totalDuration: number;
  notes: string | null;
  cancellationReason: string | null;
  recurringGroupId: string | null;
  createdAt: string;
}

export interface AppointmentServiceResponse {
  serviceId: string;
  serviceName: string;
  price: number;
  duration: number;
}

// Sayfa filtreleri
export interface AppointmentListParams {
  page: number;
  size: number;
  status?: AppointmentStatus;
  date?: string;
  staffId?: string;
  search?: string;
  sort?: string;
}
```

### 11.4 Dosya Yükleme Form Entegrasyonu

```typescript
// components/shared/file-upload.tsx
"use client";

import { useCallback, useState } from "react";
import { useDropzone } from "react-dropzone";
import { Upload, X, Image as ImageIcon } from "lucide-react";
import { cn } from "@/lib/utils/cn";

interface FileUploadProps {
  onUpload: (file: File) => Promise<string>; // URL döner
  accept?: Record<string, string[]>;
  maxSize?: number; // bytes
  value?: string; // Mevcut dosya URL'i
  onChange?: (url: string | null) => void;
}

const DEFAULT_ACCEPT = {
  "image/jpeg": [".jpg", ".jpeg"],
  "image/png": [".png"],
  "image/webp": [".webp"],
  "image/gif": [".gif"],
};

const DEFAULT_MAX_SIZE = 5 * 1024 * 1024; // 5MB — backend ile aynı

export function FileUpload({
  onUpload,
  accept = DEFAULT_ACCEPT,
  maxSize = DEFAULT_MAX_SIZE,
  value,
  onChange,
}: FileUploadProps) {
  const [isUploading, setIsUploading] = useState(false);
  const [preview, setPreview] = useState<string | null>(value || null);

  const onDrop = useCallback(
    async (acceptedFiles: File[]) => {
      const file = acceptedFiles[0];
      if (!file) return;

      setIsUploading(true);
      try {
        // Önizleme göster
        const objectUrl = URL.createObjectURL(file);
        setPreview(objectUrl);

        // Backend'e yükle
        const url = await onUpload(file);
        onChange?.(url);

        // Object URL temizle
        URL.revokeObjectURL(objectUrl);
        setPreview(url);
      } catch (error) {
        setPreview(null);
        onChange?.(null);
      } finally {
        setIsUploading(false);
      }
    },
    [onUpload, onChange]
  );

  const { getRootProps, getInputProps, isDragActive, fileRejections } =
    useDropzone({
      onDrop,
      accept,
      maxSize,
      maxFiles: 1,
    });

  return (
    <div className="space-y-2">
      <div
        {...getRootProps()}
        className={cn(
          "relative flex flex-col items-center justify-center rounded-lg border-2 border-dashed p-6 cursor-pointer transition-colors",
          isDragActive
            ? "border-primary bg-primary/5"
            : "border-muted-foreground/25 hover:border-primary/50",
          isUploading && "pointer-events-none opacity-50"
        )}
      >
        <input {...getInputProps()} />
        {preview ? (
          <div className="relative">
            <img
              src={preview}
              alt="Önizleme"
              className="max-h-40 rounded-md object-cover"
            />
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                setPreview(null);
                onChange?.(null);
              }}
              className="absolute -right-2 -top-2 rounded-full bg-destructive p-1 text-destructive-foreground"
            >
              <X className="h-3 w-3" />
            </button>
          </div>
        ) : (
          <>
            <Upload className="h-8 w-8 text-muted-foreground" />
            <p className="mt-2 text-sm text-muted-foreground">
              {isDragActive
                ? "Dosyayı bırakın..."
                : "Dosya seçin veya sürükleyin"}
            </p>
            <p className="mt-1 text-xs text-muted-foreground">
              JPG, PNG, WebP, GIF — Max {maxSize / 1024 / 1024}MB
            </p>
          </>
        )}
      </div>

      {fileRejections.length > 0 && (
        <p className="text-sm text-destructive">
          {fileRejections[0].errors[0].message === "File is larger than 5242880 bytes"
            ? "Dosya boyutu 5MB'dan büyük olamaz."
            : "Desteklenmeyen dosya formatı."}
        </p>
      )}
    </div>
  );
}
```

> **Backend referans:** `CLAUDE.md §9.9` — 5 katmanlı dosya yükleme güvenliği. Frontend client-side pre-check (boyut + format), backend'de Tika + ImageIO ile doğrulama.

---

## 12. Sayfa & Route Organizasyonu

### 12.1 App Router Grupları

Next.js App Router'da parantezli klasörler `(group)` route segment oluşturmaz — sadece organizasyon ve layout paylaşımı için kullanılır.

| Grup | Layout | Auth | Backend Scope |
|------|--------|------|---------------|
| `(public)` | Navbar + Footer | Yok | `/api/public/**` |
| `(auth)` | Minimal, centered | Yok | `/api/auth/**` |
| `(admin)` | Sidebar + Header | TENANT_ADMIN | `/api/admin/**` |
| `(client)` | Client layout | CLIENT | `/api/client/**` |
| `(staff)` | Staff layout | STAFF | `/api/staff/**` |
| `(platform)` | Platform layout | PLATFORM_ADMIN | `/api/platform/**` |

### 12.2 Layout Hiyerarşisi

```
app/layout.tsx (Root — Providers, HTML, Body)
├── (public)/layout.tsx (Public — Navbar, Footer)
│   ├── page.tsx → /
│   ├── services/page.tsx → /services
│   └── blog/[slug]/page.tsx → /blog/my-post
├── (auth)/layout.tsx (Auth — Minimal)
│   ├── login/page.tsx → /login
│   └── register/page.tsx → /register
├── (admin)/layout.tsx (Admin — AuthGuard + Sidebar + Header)
│   └── admin/
│       ├── page.tsx → /admin
│       └── appointments/page.tsx → /admin/appointments
├── (client)/layout.tsx (Client — AuthGuard + Client layout)
│   └── portal/
│       ├── page.tsx → /portal
│       └── appointments/page.tsx → /portal/appointments
└── (staff)/layout.tsx (Staff — AuthGuard + Staff layout)
    └── staff/
        └── calendar/page.tsx → /staff/calendar
```

### 12.3 Root Layout

```typescript
// app/layout.tsx
import type { Metadata } from "next";
import { Inter } from "next/font/google";
import { AppProviders } from "@/lib/providers/app-providers";
import "@/styles/globals.css";

const inter = Inter({ subsets: ["latin"] });

export const metadata: Metadata = {
  title: {
    template: "%s | Aesthetic SaaS",
    default: "Aesthetic SaaS",
  },
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="tr" suppressHydrationWarning>
      <body className={inter.className}>
        <AppProviders>{children}</AppProviders>
      </body>
    </html>
  );
}
```

### 12.4 App Providers — Provider Birleştirme

```typescript
// lib/providers/app-providers.tsx
"use client";

import { type ReactNode } from "react";
import { QueryProvider } from "./query-provider";
import { TenantProvider } from "./tenant-provider";
import { AuthProvider } from "./auth-provider";
import { ThemeProvider } from "./theme-provider";
import { TerminologyProvider } from "./terminology-provider";
import { Toaster } from "sonner";

interface AppProvidersProps {
  children: ReactNode;
  tenantSlug?: string;
}

export function AppProviders({ children, tenantSlug }: AppProvidersProps) {
  return (
    <QueryProvider>
      <TenantProvider slug={tenantSlug || ""}>
        <AuthProvider>
          <ThemeProvider>
            <TerminologyProvider>
              {children}
              <Toaster richColors position="top-right" />
            </TerminologyProvider>
          </ThemeProvider>
        </AuthProvider>
      </TenantProvider>
    </QueryProvider>
  );
}
```

### 12.5 Dinamik Route'lar (Slug-Based)

```typescript
// app/(public)/services/[slug]/page.tsx
import { notFound } from "next/navigation";
import { apiClient } from "@/lib/api/client";
import type { ServiceResponse } from "@/features/services/types";

interface Props {
  params: Promise<{ slug: string }>;
}

// ISR — 60 saniyede bir yeniden oluştur
export const revalidate = 60;

export async function generateMetadata({ params }: Props) {
  const { slug } = await params;
  const service = await apiClient.get<ServiceResponse>(
    `/api/public/services/${slug}`
  );

  if (!service) return {};

  return {
    title: service.name,
    description: service.description,
    openGraph: {
      title: service.name,
      description: service.description,
      images: service.imageUrl ? [{ url: service.imageUrl }] : [],
    },
  };
}

export default async function ServiceDetailPage({ params }: Props) {
  const { slug } = await params;
  const service = await apiClient.get<ServiceResponse>(
    `/api/public/services/${slug}`
  );

  if (!service) notFound();

  return (
    <article className="container mx-auto py-8">
      <h1 className="text-3xl font-bold">{service.name}</h1>
      {/* ... */}
    </article>
  );
}
```

---

## 13. Component Mimarisi

### 13.1 Component Katmanları

```
┌─────────────────────────────────────────────┐
│              Page Components                 │
│  (app/(admin)/admin/appointments/page.tsx)   │
├─────────────────────────────────────────────┤
│           Feature Components                 │
│  (features/appointments/components/*)        │
├─────────────────────────────────────────────┤
│           Shared Components                  │
│  (components/shared/data-table.tsx)          │
├─────────────────────────────────────────────┤
│            Guard Components                  │
│  (components/guards/auth-guard.tsx)          │
├─────────────────────────────────────────────┤
│          Layout Components                   │
│  (components/layout/admin-sidebar.tsx)       │
├─────────────────────────────────────────────┤
│        UI Primitives (shadcn/ui)             │
│  (components/ui/button.tsx)                  │
└─────────────────────────────────────────────┘
```

### 13.2 DataTable — TanStack Table Wrapper

```typescript
// components/shared/data-table.tsx
"use client";

import {
  type ColumnDef,
  type ColumnFiltersState,
  type SortingState,
  type VisibilityState,
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getSortedRowModel,
  useReactTable,
} from "@tanstack/react-table";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useState } from "react";

interface DataTableProps<TData, TValue> {
  columns: ColumnDef<TData, TValue>[];
  data: TData[];
  isLoading?: boolean;
  emptyMessage?: string;
}

export function DataTable<TData, TValue>({
  columns,
  data,
  isLoading,
  emptyMessage = "Kayıt bulunamadı.",
}: DataTableProps<TData, TValue>) {
  const [sorting, setSorting] = useState<SortingState>([]);
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);
  const [columnVisibility, setColumnVisibility] = useState<VisibilityState>({});

  const table = useReactTable({
    data,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    onSortingChange: setSorting,
    onColumnFiltersChange: setColumnFilters,
    onColumnVisibilityChange: setColumnVisibility,
    state: {
      sorting,
      columnFilters,
      columnVisibility,
    },
  });

  return (
    <div className="rounded-md border">
      <Table>
        <TableHeader>
          {table.getHeaderGroups().map((headerGroup) => (
            <TableRow key={headerGroup.id}>
              {headerGroup.headers.map((header) => (
                <TableHead key={header.id}>
                  {header.isPlaceholder
                    ? null
                    : flexRender(
                        header.column.columnDef.header,
                        header.getContext()
                      )}
                </TableHead>
              ))}
            </TableRow>
          ))}
        </TableHeader>
        <TableBody>
          {isLoading ? (
            <TableRow>
              <TableCell colSpan={columns.length} className="h-24 text-center">
                Yükleniyor...
              </TableCell>
            </TableRow>
          ) : table.getRowModel().rows.length > 0 ? (
            table.getRowModel().rows.map((row) => (
              <TableRow key={row.id}>
                {row.getVisibleCells().map((cell) => (
                  <TableCell key={cell.id}>
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </TableCell>
                ))}
              </TableRow>
            ))
          ) : (
            <TableRow>
              <TableCell colSpan={columns.length} className="h-24 text-center">
                {emptyMessage}
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  );
}
```

### 13.3 AppointmentStatusBadge — Status Renkleri

```typescript
// features/appointments/components/appointment-status-badge.tsx
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils/cn";
import type { AppointmentStatus } from "../types";

const STATUS_CONFIG: Record<
  AppointmentStatus,
  { label: string; variant: string }
> = {
  PENDING: { label: "Bekliyor", variant: "bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400" },
  CONFIRMED: { label: "Onaylandı", variant: "bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400" },
  IN_PROGRESS: { label: "Devam Ediyor", variant: "bg-purple-100 text-purple-800 dark:bg-purple-900/30 dark:text-purple-400" },
  COMPLETED: { label: "Tamamlandı", variant: "bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400" },
  CANCELLED: { label: "İptal Edildi", variant: "bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400" },
  NO_SHOW: { label: "Gelmedi", variant: "bg-gray-100 text-gray-800 dark:bg-gray-900/30 dark:text-gray-400" },
};

export function AppointmentStatusBadge({
  status,
}: {
  status: AppointmentStatus;
}) {
  const config = STATUS_CONFIG[status];
  return (
    <Badge variant="outline" className={cn("font-medium", config.variant)}>
      {config.label}
    </Badge>
  );
}
```

---

## 14. SEO Stratejisi

### 14.1 SSG + ISR Kullanımı

Public sayfalar SEO için kritiktir. Next.js SSG (Static Site Generation) ve ISR (Incremental Static Regeneration) kullanılır.

| Sayfa | Strateji | revalidate | Neden |
|-------|----------|------------|-------|
| Ana sayfa | ISR | 300 (5 dk) | Sık değişmez, statik yeterli |
| Hizmetler listesi | ISR | 60 (1 dk) | Hizmet ekleme/güncelleme sonrası güncellenmeli |
| Hizmet detay | ISR | 60 | Fiyat/açıklama değişebilir |
| Blog listesi | ISR | 120 (2 dk) | Yeni yazı ekleme nadirdir |
| Blog detay | ISR | 300 | İçerik nadiren güncellenir |
| Ürünler | ISR | 60 | Stok/fiyat değişebilir |
| Galeri | ISR | 300 | Nadiren güncellenir |
| Değerlendirmeler | ISR | 120 | Yeni değerlendirme gelir |
| Randevu alma | SSR | - | Gerçek zamanlı müsaitlik gerekli |

### 14.2 generateMetadata

```typescript
// app/(public)/blog/[slug]/page.tsx
import type { Metadata } from "next";
import { apiClient } from "@/lib/api/client";
import type { BlogPostResponse } from "@/features/blog/types";

interface Props {
  params: Promise<{ slug: string }>;
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { slug } = await params;
  const post = await apiClient.get<BlogPostResponse>(
    `/api/public/blog/${slug}`
  );

  if (!post) return { title: "Blog Yazısı Bulunamadı" };

  return {
    title: post.title,
    description: post.excerpt || post.content.slice(0, 160),
    authors: [{ name: post.authorName }],
    openGraph: {
      title: post.title,
      description: post.excerpt || post.content.slice(0, 160),
      type: "article",
      publishedTime: post.publishedAt,
      authors: [post.authorName],
      images: post.coverImageUrl
        ? [{ url: post.coverImageUrl, width: 1200, height: 630 }]
        : [],
    },
    twitter: {
      card: "summary_large_image",
      title: post.title,
      description: post.excerpt || post.content.slice(0, 160),
    },
  };
}
```

### 14.3 JSON-LD Structured Data

```typescript
// lib/utils/seo.ts

// İşletme bilgisi (LocalBusiness schema)
export function generateLocalBusinessJsonLd(tenant: TenantConfig) {
  return {
    "@context": "https://schema.org",
    "@type": "LocalBusiness",
    name: tenant.name,
    telephone: tenant.phone,
    email: tenant.email,
    address: {
      "@type": "PostalAddress",
      streetAddress: tenant.address,
    },
    url: `https://${tenant.slug}.app.com`,
    image: tenant.logoUrl,
  };
}

// Hizmet (Service schema)
export function generateServiceJsonLd(
  service: ServiceResponse,
  tenant: TenantConfig
) {
  return {
    "@context": "https://schema.org",
    "@type": "Service",
    name: service.name,
    description: service.description,
    provider: {
      "@type": "LocalBusiness",
      name: tenant.name,
    },
    offers: {
      "@type": "Offer",
      price: service.price,
      priceCurrency: tenant.settings?.currency || "TRY",
    },
  };
}
```

```typescript
// Sayfa içinde kullanım
export default function ServiceDetailPage({ service, tenant }) {
  const jsonLd = generateServiceJsonLd(service, tenant);

  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <article>{/* ... */}</article>
    </>
  );
}
```

---

## 15. i18n (Çoklu Dil)

### 15.1 next-intl Kurulumu

```typescript
// lib/i18n/config.ts
export const locales = ["tr", "en"] as const;
export type Locale = (typeof locales)[number];
export const defaultLocale: Locale = "tr";
```

### 15.2 Messages Yapısı

```
src/messages/
├── tr/
│   ├── common.json           # Ortak terimler
│   ├── auth.json             # Giriş/kayıt
│   ├── appointments.json     # Randevu modülü
│   ├── services.json         # Hizmetler
│   ├── dashboard.json        # Dashboard
│   ├── settings.json         # Ayarlar
│   ├── errors.json           # Hata mesajları
│   └── validation.json       # Form doğrulama mesajları
└── en/
    ├── common.json
    ├── auth.json
    ├── appointments.json
    └── ...
```

```json
// messages/tr/common.json
{
  "navigation": {
    "home": "Ana Sayfa",
    "services": "Hizmetler",
    "blog": "Blog",
    "gallery": "Galeri",
    "contact": "İletişim",
    "booking": "Randevu Al",
    "login": "Giriş",
    "register": "Kayıt Ol"
  },
  "actions": {
    "save": "Kaydet",
    "cancel": "İptal",
    "delete": "Sil",
    "edit": "Düzenle",
    "create": "Oluştur",
    "search": "Ara",
    "filter": "Filtrele",
    "export": "Dışa Aktar",
    "back": "Geri",
    "next": "İleri",
    "confirm": "Onayla"
  },
  "status": {
    "active": "Aktif",
    "inactive": "Pasif",
    "loading": "Yükleniyor...",
    "noResults": "Sonuç bulunamadı",
    "error": "Bir hata oluştu"
  }
}
```

```json
// messages/tr/errors.json — Backend ErrorCode mapping
{
  "VALIDATION_ERROR": "Lütfen formu kontrol edin.",
  "INVALID_CREDENTIALS": "E-posta veya şifre hatalı.",
  "FORBIDDEN": "Bu işlem için yetkiniz yok.",
  "PLAN_LIMIT_EXCEEDED": "Plan limitinize ulaştınız. Planınızı yükseltin.",
  "CLIENT_BLACKLISTED": "Hesabınız kara listeye alınmıştır.",
  "RESOURCE_NOT_FOUND": "Aradığınız kayıt bulunamadı.",
  "TENANT_NOT_FOUND": "İşletme bulunamadı veya aktif değil.",
  "APPOINTMENT_CONFLICT": "Seçilen zaman diliminde çakışma var.",
  "DUPLICATE_RESOURCE": "Bu kayıt zaten mevcut.",
  "ACCOUNT_LOCKED": "Hesabınız çok fazla başarısız giriş nedeniyle kilitlendi. 15 dakika sonra tekrar deneyin.",
  "INTERNAL_ERROR": "Beklenmeyen bir hata oluştu. Lütfen tekrar deneyin."
}
```

### 15.3 Kullanım

```typescript
// Component içinde
"use client";

import { useTranslations } from "next-intl";

export function AppointmentActions() {
  const t = useTranslations("actions");
  const tStatus = useTranslations("status");

  return (
    <div>
      <Button>{t("save")}</Button>        {/* "Kaydet" */}
      <Button variant="outline">{t("cancel")}</Button> {/* "İptal" */}
      <span>{tStatus("loading")}</span>   {/* "Yükleniyor..." */}
    </div>
  );
}
```

### 15.4 Terminoloji Sistemi ile Entegrasyon

Terminoloji sistemi (§9) ve i18n birlikte çalışır. `useTerminology` hook'u BusinessType'a göre terim döndürür, `useTranslations` ise locale'e göre çeviri döndürür.

```typescript
// Önerilen kullanım:
// - Statik UI metinleri → useTranslations (next-intl)
// - BusinessType'a bağlı terimler → useTerminology

function PageTitle() {
  const t = useTranslations("navigation");
  const term = useTerminology();

  return (
    <h1>
      {term("appointments")} {/* "Seanslar" / "Muayeneler" / "Randevular" */}
    </h1>
  );
}
```

---

## 16. Randevu Sistemi (Frontend)

### 16.1 Randevu Status State Machine

Backend'deki state machine ile birebir eşleşir. Frontend'de geçersiz durum geçişleri engellenir.

```typescript
// features/appointments/lib/status-transitions.ts
import type { AppointmentStatus } from "../types";

// Backend ile aynı geçiş kuralları (BACKEND_ARCHITECTURE.md §5.2)
const VALID_TRANSITIONS: Record<AppointmentStatus, AppointmentStatus[]> = {
  PENDING: ["CONFIRMED", "CANCELLED"],
  CONFIRMED: ["IN_PROGRESS", "CANCELLED", "NO_SHOW"],
  IN_PROGRESS: ["COMPLETED"],
  COMPLETED: [],     // Son durum — geçiş yok
  CANCELLED: [],     // Son durum — geçiş yok
  NO_SHOW: [],       // Son durum — geçiş yok
};

export function getValidTransitions(
  currentStatus: AppointmentStatus
): AppointmentStatus[] {
  return VALID_TRANSITIONS[currentStatus] ?? [];
}

export function canTransitionTo(
  from: AppointmentStatus,
  to: AppointmentStatus
): boolean {
  return VALID_TRANSITIONS[from]?.includes(to) ?? false;
}

export function isTerminalStatus(status: AppointmentStatus): boolean {
  return VALID_TRANSITIONS[status]?.length === 0;
}
```

```
Status State Machine (Backend ile aynı):

  PENDING ──→ CONFIRMED ──→ IN_PROGRESS ──→ COMPLETED
     │            │
     └──→ CANCELLED  ├──→ CANCELLED
                     └──→ NO_SHOW
```

### 16.2 Randevu Alma (Booking) Akışı

```
Booking Wizard (4 Adım):

┌────────────┐    ┌────────────┐    ┌────────────┐    ┌────────────┐
│ 1. Hizmet  │──►│ 2. Tarih   │──►│ 3. Bilgi   │──►│ 4. Onay    │
│    Seçimi  │    │    & Saat  │    │    Formu   │    │            │
│            │    │    & Staff │    │            │    │            │
│ Multi-     │    │ FullCalendar│    │ Ad, Soyad │    │ Özet       │
│ select     │    │ Slot picker │    │ Email, Tel │    │ Ödeme?     │
└────────────┘    └────────────┘    └────────────┘    └────────────┘
                        │
                        ▼
                  ┌─────────────┐
                  │ Redis Slot  │
                  │ Reservation │
                  │ (5 dk hold) │
                  └─────────────┘
```

### 16.3 Slot Reservation UX (Redis 5dk Hold)

```typescript
// features/appointments/components/slot-picker.tsx
"use client";

import { useState, useEffect, useCallback } from "react";
import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";
import { apiClient } from "@/lib/api/client";
import { useBookingStore } from "@/lib/store/booking-store";
import type { AvailableSlot } from "../types";

interface SlotPickerProps {
  slots: AvailableSlot[];
  onSlotSelect: (slot: AvailableSlot) => void;
}

export function SlotPicker({ slots, onSlotSelect }: SlotPickerProps) {
  const { reservationId, reservationExpiresAt, setReservation } =
    useBookingStore();
  const [countdown, setCountdown] = useState<number | null>(null);

  // Slot reserve mutation
  const reserveMutation = useMutation({
    mutationFn: (slot: AvailableSlot) =>
      apiClient.post<{ reservationId: string; expiresAt: number }>(
        "/api/public/appointments/reserve-slot",
        {
          staffId: slot.staffId,
          date: slot.date,
          startTime: slot.startTime,
        }
      ),
    onSuccess: (data, slot) => {
      setReservation(data.reservationId, data.expiresAt);
      onSlotSelect(slot);
      toast.success("Slot 5 dakika boyunca sizin için ayrıldı.");
    },
    onError: (error) => {
      toast.error("Bu slot başka biri tarafından seçildi. Lütfen başka bir saat seçin.");
    },
  });

  // Countdown timer
  useEffect(() => {
    if (!reservationExpiresAt) return;

    const interval = setInterval(() => {
      const remaining = Math.max(
        0,
        Math.floor((reservationExpiresAt - Date.now()) / 1000)
      );
      setCountdown(remaining);

      if (remaining === 0) {
        toast.warning("Slot rezervasyonunuz sona erdi. Lütfen tekrar seçin.");
        useBookingStore.getState().reset();
      }

      // Son 60 saniyede uyarı
      if (remaining === 60) {
        toast.warning("Slot rezervasyonunuz 1 dakika sonra sona erecek!");
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [reservationExpiresAt]);

  const formatCountdown = (seconds: number): string => {
    const min = Math.floor(seconds / 60);
    const sec = seconds % 60;
    return `${min}:${sec.toString().padStart(2, "0")}`;
  };

  return (
    <div className="space-y-4">
      {/* Countdown banner */}
      {countdown !== null && countdown > 0 && (
        <div className="flex items-center justify-between rounded-lg border border-primary/20 bg-primary/5 p-3">
          <span className="text-sm">Slot ayrıldı</span>
          <span className="font-mono text-lg font-bold text-primary">
            {formatCountdown(countdown)}
          </span>
        </div>
      )}

      {/* Slot grid */}
      <div className="grid grid-cols-4 gap-2 sm:grid-cols-6">
        {slots.map((slot) => (
          <button
            key={`${slot.staffId}-${slot.startTime}`}
            onClick={() => reserveMutation.mutate(slot)}
            disabled={reserveMutation.isPending || !slot.isAvailable}
            className={cn(
              "rounded-md border px-3 py-2 text-sm transition-colors",
              slot.isAvailable
                ? "border-border hover:border-primary hover:bg-primary/5 cursor-pointer"
                : "border-muted bg-muted/50 text-muted-foreground cursor-not-allowed"
            )}
          >
            {slot.startTime}
          </button>
        ))}
      </div>
    </div>
  );
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §28.10/16-3.1` — Redis SETNX ile 5 dk slot reservation. Key: `slot_reservation:{tenantId}:{staffId}:{date}:{time}`.

### 16.4 Reschedule UI

```typescript
// features/appointments/components/reschedule-dialog.tsx
"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { toast } from "sonner";
import { apiClient } from "@/lib/api/client";
import { queryKeys } from "@/lib/constants/query-keys";
import { SlotPicker } from "./slot-picker";
import { useAvailableSlots } from "../api/use-availability";
import type { AppointmentResponse, AvailableSlot } from "../types";

interface RescheduleDialogProps {
  appointment: AppointmentResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function RescheduleDialog({
  appointment,
  open,
  onOpenChange,
}: RescheduleDialogProps) {
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const serviceIds = appointment.services.map((s) => s.serviceId);
  const { data: slots } = useAvailableSlots(
    selectedDate || "",
    serviceIds
  );

  const rescheduleMutation = useMutation({
    mutationFn: (data: { date: string; startTime: string; staffId: string | null }) =>
      apiClient.patch<AppointmentResponse>(
        `/api/admin/appointments/${appointment.id}/reschedule`,
        data
      ),
    onSuccess: () => {
      toast.success("Randevu yeni tarihe taşındı.");
      queryClient.invalidateQueries({
        queryKey: queryKeys.appointments.all(),
      });
      onOpenChange(false);
    },
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Randevuyu Yeniden Planla</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div>
            <p className="text-sm text-muted-foreground">
              Mevcut: {appointment.date} {appointment.startTime} —{" "}
              {appointment.staffName}
            </p>
          </div>
          {/* Tarih seçici */}
          {/* Slot seçici */}
          {selectedDate && slots && (
            <SlotPicker
              slots={slots}
              onSlotSelect={(slot) =>
                rescheduleMutation.mutate({
                  date: slot.date,
                  startTime: slot.startTime,
                  staffId: slot.staffId,
                })
              }
            />
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §28.2` — `AppointmentService.rescheduleAppointment()`.

### 16.5 Auto-Confirm Farkındalığı

```typescript
// features/appointments/components/booking-confirmation.tsx
import { useTenant } from "@/lib/providers/tenant-provider";

export function BookingConfirmation({ appointment }: { appointment: AppointmentResponse }) {
  const { tenant } = useTenant();
  const autoConfirm = tenant?.settings?.autoConfirmAppointments ?? false;

  return (
    <div className="text-center space-y-4">
      <div className="text-4xl">
        {autoConfirm ? "✅" : "⏳"}
      </div>
      <h2 className="text-xl font-semibold">
        {autoConfirm
          ? "Randevunuz onaylandı!"
          : "Randevunuz alındı!"}
      </h2>
      <p className="text-muted-foreground">
        {autoConfirm
          ? "Randevunuz otomatik olarak onaylanmıştır."
          : "Randevunuz işletme tarafından onaylandıktan sonra size bildirim gönderilecektir."}
      </p>
    </div>
  );
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §28.7` — `SiteSettings.autoConfirmAppointments`.

### 16.6 DST Geçiş Uyarısı

```typescript
// features/appointments/lib/time-slot-utils.ts
import { toZonedTime, fromZonedTime } from "date-fns-tz";

/**
 * DST (Yaz Saati) geçişi kontrolü.
 * Randevu tarihi DST geçiş gününe denk geliyorsa kullanıcıyı uyar.
 */
export function checkDstTransition(
  date: string,
  timezone: string
): { isDstTransition: boolean; message: string | null } {
  const targetDate = new Date(`${date}T12:00:00`);
  const dayBefore = new Date(targetDate);
  dayBefore.setDate(dayBefore.getDate() - 1);

  const zonedTarget = toZonedTime(targetDate, timezone);
  const zonedBefore = toZonedTime(dayBefore, timezone);

  const offsetTarget = zonedTarget.getTimezoneOffset();
  const offsetBefore = zonedBefore.getTimezoneOffset();

  if (offsetTarget !== offsetBefore) {
    const diff = offsetBefore - offsetTarget;
    return {
      isDstTransition: true,
      message:
        diff > 0
          ? `Bu tarihte saat ${Math.abs(diff / 60)} saat ileri alınacaktır. Randevu saatinizi kontrol edin.`
          : `Bu tarihte saat ${Math.abs(diff / 60)} saat geri alınacaktır. Randevu saatinizi kontrol edin.`,
    };
  }

  return { isDstTransition: false, message: null };
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §28.10/16-3.2` — DST geçiş kontrolü.

---

## 17. Hasta Kayıtları (Patient Records)

### 17.1 BusinessType'a Göre Dinamik Form

```typescript
// features/patient-records/components/patient-record-form.tsx
"use client";

import { useTenant } from "@/lib/providers/tenant-provider";
import { BeautyClinicRecordForm } from "./forms/beauty-clinic-form";
import { DentalClinicRecordForm } from "./forms/dental-clinic-form";
import { VeterinaryRecordForm } from "./forms/veterinary-form";
import { GenericRecordForm } from "./forms/generic-form";
import type { BusinessType } from "@/types/tenant";

// Strategy pattern — BusinessType'a göre form seçimi
const RECORD_FORMS: Record<BusinessType, React.ComponentType<RecordFormProps>> = {
  BEAUTY_CLINIC: BeautyClinicRecordForm,
  DENTAL_CLINIC: DentalClinicRecordForm,
  VETERINARY: VeterinaryRecordForm,
  BARBER_SHOP: GenericRecordForm,
  HAIR_SALON: GenericRecordForm,
  DIETITIAN: GenericRecordForm,
  PHYSIOTHERAPIST: GenericRecordForm,
  MASSAGE_SALON: GenericRecordForm,
  GENERAL: GenericRecordForm,
};

export function PatientRecordForm(props: RecordFormProps) {
  const { businessType } = useTenant();
  const FormComponent = RECORD_FORMS[businessType];
  return <FormComponent {...props} />;
}
```

### 17.2 Hassas Veri Gösterimi

Backend AES-256 ile şifrelenen alanlar (TC kimlik, sağlık geçmişi vb.) backend'de çözülerek gönderilir. Frontend'de özel maskeleme yapılır.

```typescript
// features/patient-records/components/sensitive-field.tsx
"use client";

import { useState } from "react";
import { Eye, EyeOff } from "lucide-react";
import { Button } from "@/components/ui/button";

interface SensitiveFieldProps {
  label: string;
  value: string;
  maskChar?: string;
}

export function SensitiveField({
  label,
  value,
  maskChar = "*",
}: SensitiveFieldProps) {
  const [isVisible, setIsVisible] = useState(false);

  const maskedValue = value
    ? value.slice(0, 2) + maskChar.repeat(value.length - 4) + value.slice(-2)
    : "—";

  return (
    <div className="flex items-center justify-between">
      <div>
        <span className="text-sm text-muted-foreground">{label}</span>
        <p className="font-mono">{isVisible ? value : maskedValue}</p>
      </div>
      <Button
        variant="ghost"
        size="icon"
        onClick={() => setIsVisible(!isVisible)}
        aria-label={isVisible ? "Gizle" : "Göster"}
      >
        {isVisible ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
      </Button>
    </div>
  );
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §28.10/16-4.1` — AES-256 encrypted fields.

---

## 18. Dashboard & Raporlama

### 18.1 Dashboard Stats Tipi

```typescript
// features/dashboard/types/index.ts

// Backend DashboardStats DTO karşılığı
export interface DashboardStats {
  totalAppointments: number;
  completedAppointments: number;
  pendingAppointments: number;
  cancelledAppointments: number;
  todayRevenue: number;
  monthlyRevenue: number;
  totalClients: number;
  newClientsThisMonth: number;
  averageRating: number;
  totalReviews: number;
}

export interface WeeklyData {
  date: string;
  appointments: number;
  revenue: number;
}
```

### 18.2 Dashboard Hook — Polling ile Gerçek Zamanlı

```typescript
// features/dashboard/api/use-dashboard-stats.ts
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";
import { queryKeys } from "@/lib/constants/query-keys";
import type { DashboardStats } from "../types";
import { format } from "date-fns";

export function useDashboardStats() {
  const today = format(new Date(), "yyyy-MM-dd");

  return useQuery({
    queryKey: queryKeys.dashboard.stats(today),
    queryFn: () =>
      apiClient.get<DashboardStats>("/api/admin/dashboard/stats", {
        date: today,
      }),
    staleTime: 60 * 1000, // 1 dk
    refetchInterval: 2 * 60 * 1000, // 2 dk'da bir yenile
  });
}
```

### 18.3 Recharts Entegrasyonu

```typescript
// features/dashboard/components/revenue-chart.tsx
"use client";

import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { WeeklyData } from "../types";

export function RevenueChart({ data }: { data: WeeklyData[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Haftalık Gelir</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="h-[300px]">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={data}>
              <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
              <XAxis
                dataKey="date"
                className="text-xs text-muted-foreground"
              />
              <YAxis className="text-xs text-muted-foreground" />
              <Tooltip
                formatter={(value: number) =>
                  new Intl.NumberFormat("tr-TR", {
                    style: "currency",
                    currency: "TRY",
                  }).format(value)
                }
              />
              <Area
                type="monotone"
                dataKey="revenue"
                stroke="hsl(var(--primary))"
                fill="hsl(var(--primary) / 0.1)"
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </CardContent>
    </Card>
  );
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §18.1` — Dashboard tek query ile aggregate. `CLAUDE.md §18.1` — Dashboard Aggregate Query.

---

## 19. Bildirim & Real-time

### 19.1 Bildirim Polling

Real-time için WebSocket yerine TanStack Query `refetchInterval` ile polling kullanılır.

```typescript
// features/notifications/api/use-notifications.ts
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";
import { queryKeys } from "@/lib/constants/query-keys";

export function useUnreadNotificationCount() {
  return useQuery({
    queryKey: queryKeys.notifications.unreadCount(),
    queryFn: () =>
      apiClient.get<{ count: number }>("/api/admin/notifications/unread-count"),
    refetchInterval: 30 * 1000, // 30 sn'de bir kontrol et
    staleTime: 10 * 1000,
  });
}
```

### 19.2 Toast (Sonner)

```typescript
// features/notifications/components/notification-toast.tsx
import { toast } from "sonner";

// API hatası toast
export function showApiError(error: unknown) {
  const message = getErrorMessage(error);
  toast.error(message);
}

// Başarı toast
export function showSuccess(message: string) {
  toast.success(message);
}

// Uyarı toast (plan limiti yaklaşıyor)
export function showPlanLimitWarning(current: number, limit: number) {
  const percentage = Math.round((current / limit) * 100);
  if (percentage >= 80) {
    toast.warning(
      `Plan limitinizin %${percentage}'ına ulaştınız (${current}/${limit}). Planınızı yükseltmeyi düşünün.`
    );
  }
}
```

### 19.3 Admin Bildirim Template Yönetimi

```typescript
// features/notifications/types/index.ts

// Backend'deki 11 varsayılan bildirim template tipi
export type NotificationTemplateType =
  | "APPOINTMENT_CONFIRMATION"
  | "APPOINTMENT_REMINDER_24H"
  | "APPOINTMENT_REMINDER_1H"
  | "APPOINTMENT_CANCELLED"
  | "APPOINTMENT_RESCHEDULED"
  | "WELCOME"
  | "PASSWORD_RESET"
  | "REVIEW_REQUEST"
  | "NO_SHOW_NOTICE"
  | "CLIENT_BLACKLISTED_NOTICE"
  | "PAYMENT_RECEIPT";

export interface NotificationTemplate {
  id: string;
  type: NotificationTemplateType;
  subject: string;
  body: string; // {{variable}} syntax
  isActive: boolean;
  channel: "EMAIL" | "SMS" | "BOTH";
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §28.6` — 11 varsayılan bildirim template'i.

---

## 20. Dosya Yükleme

### 20.1 Upload Akışı

```
react-dropzone → Client-side validation → Backend upload → S3
                  (boyut + tip kontrolü)    (5 katmanlı güvenlik)
                                                    ↓
                                           Presigned URL ile gösterim
```

### 20.2 Upload Hook

```typescript
// features/uploads/api/use-upload.ts
import { useMutation } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";

interface UploadResponse {
  url: string;        // S3 key (doğrudan erişilmez!)
  presignedUrl: string; // Geçici görüntüleme URL'i
  filename: string;
}

export function useUploadFile() {
  return useMutation({
    mutationFn: async (file: File) => {
      const formData = new FormData();
      formData.append("file", file);
      return apiClient.upload<UploadResponse>("/api/admin/upload", formData);
    },
  });
}
```

### 20.3 Presigned URL ile Görüntüleme

```typescript
// components/shared/secure-image.tsx
"use client";

import { useState, useEffect } from "react";
import Image from "next/image";
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";

interface SecureImageProps {
  fileKey: string; // S3 key
  alt: string;
  width: number;
  height: number;
  className?: string;
}

/**
 * S3'teki dosyayı presigned URL ile gösterir.
 * KRİTİK: Public S3 URL'i ASLA kullanma — her zaman presigned URL.
 */
export function SecureImage({
  fileKey,
  alt,
  width,
  height,
  className,
}: SecureImageProps) {
  const { data: presignedUrl, isLoading } = useQuery({
    queryKey: ["presigned-url", fileKey],
    queryFn: () =>
      apiClient.get<string>("/api/admin/files/presigned-url", {
        key: fileKey,
      }),
    staleTime: 14 * 60 * 1000, // 14 dk (presigned URL 15 dk geçerli)
    gcTime: 15 * 60 * 1000,
    enabled: !!fileKey,
  });

  if (isLoading || !presignedUrl) {
    return (
      <div
        className="animate-pulse bg-muted rounded"
        style={{ width, height }}
      />
    );
  }

  return (
    <Image
      src={presignedUrl}
      alt={alt}
      width={width}
      height={height}
      className={className}
      unoptimized // Presigned URL next/image optimization bypass
    />
  );
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §28.10/16-4.3` — S3 Presigned URL. Public URL yerine ömürlü presigned URL kullanılır.

---

## 21. Rich Text Editor

### 21.1 Tiptap Entegrasyonu

```typescript
// components/shared/rich-text-editor.tsx
"use client";

import { useEditor, EditorContent } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import ImageExtension from "@tiptap/extension-image";
import LinkExtension from "@tiptap/extension-link";
import PlaceholderExtension from "@tiptap/extension-placeholder";
import {
  Bold,
  Italic,
  List,
  ListOrdered,
  Heading1,
  Heading2,
  Link,
  Image as ImageIcon,
  Undo,
  Redo,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils/cn";

interface RichTextEditorProps {
  content: string;
  onChange: (content: string) => void;
  placeholder?: string;
  className?: string;
}

export function RichTextEditor({
  content,
  onChange,
  placeholder = "İçerik yazın...",
  className,
}: RichTextEditorProps) {
  const editor = useEditor({
    extensions: [
      StarterKit.configure({
        heading: { levels: [1, 2, 3] },
      }),
      LinkExtension.configure({
        openOnClick: false,
        HTMLAttributes: { rel: "noopener noreferrer", target: "_blank" },
      }),
      ImageExtension,
      PlaceholderExtension.configure({ placeholder }),
    ],
    content,
    onUpdate: ({ editor }) => {
      onChange(editor.getHTML());
    },
  });

  if (!editor) return null;

  return (
    <div className={cn("rounded-md border", className)}>
      {/* Toolbar */}
      <div className="flex flex-wrap gap-1 border-b p-2">
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={() => editor.chain().focus().toggleBold().run()}
          className={cn(editor.isActive("bold") && "bg-muted")}
        >
          <Bold className="h-4 w-4" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={() => editor.chain().focus().toggleItalic().run()}
          className={cn(editor.isActive("italic") && "bg-muted")}
        >
          <Italic className="h-4 w-4" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={() =>
            editor.chain().focus().toggleHeading({ level: 1 }).run()
          }
          className={cn(editor.isActive("heading", { level: 1 }) && "bg-muted")}
        >
          <Heading1 className="h-4 w-4" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={() =>
            editor.chain().focus().toggleHeading({ level: 2 }).run()
          }
          className={cn(editor.isActive("heading", { level: 2 }) && "bg-muted")}
        >
          <Heading2 className="h-4 w-4" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={() => editor.chain().focus().toggleBulletList().run()}
          className={cn(editor.isActive("bulletList") && "bg-muted")}
        >
          <List className="h-4 w-4" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={() => editor.chain().focus().toggleOrderedList().run()}
          className={cn(editor.isActive("orderedList") && "bg-muted")}
        >
          <ListOrdered className="h-4 w-4" />
        </Button>
        <div className="mx-1 w-px bg-border" />
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={() => editor.chain().focus().undo().run()}
        >
          <Undo className="h-4 w-4" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={() => editor.chain().focus().redo().run()}
        >
          <Redo className="h-4 w-4" />
        </Button>
      </div>

      {/* Editor */}
      <EditorContent
        editor={editor}
        className="prose prose-sm dark:prose-invert max-w-none p-4 min-h-[200px] focus-within:outline-none"
      />
    </div>
  );
}
```

### 21.2 HTML Sanitization (Gösterim)

```typescript
// components/shared/rich-text-viewer.tsx
import DOMPurify from "dompurify";

interface RichTextViewerProps {
  content: string;
  className?: string;
}

export function RichTextViewer({ content, className }: RichTextViewerProps) {
  // XSS koruması — DOMPurify ile sanitize
  const sanitizedHtml = DOMPurify.sanitize(content, {
    ALLOWED_TAGS: [
      "p", "br", "strong", "em", "u", "h1", "h2", "h3",
      "ul", "ol", "li", "a", "img", "blockquote", "code", "pre",
    ],
    ALLOWED_ATTR: ["href", "src", "alt", "target", "rel", "class"],
  });

  return (
    <div
      className={cn("prose prose-sm dark:prose-invert max-w-none", className)}
      dangerouslySetInnerHTML={{ __html: sanitizedHtml }}
    />
  );
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §28.10/AA-D3` — Yazarı silinmiş blog post'lar filtrelenmeli.

---

## 22. Error Handling & Monitoring

### 22.1 Sentry Kurulumu

```typescript
// sentry.client.config.ts
import * as Sentry from "@sentry/nextjs";

Sentry.init({
  dsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
  environment: process.env.NODE_ENV,
  tracesSampleRate: process.env.NODE_ENV === "production" ? 0.1 : 1.0,
  replaysSessionSampleRate: 0.1,
  replaysOnErrorSampleRate: 1.0,

  integrations: [
    Sentry.replayIntegration(),
  ],

  // Kullanıcı bilgilerini anonim olarak gönder
  beforeSend(event) {
    if (event.user) {
      delete event.user.email;
      delete event.user.ip_address;
    }
    return event;
  },
});
```

### 22.2 Error Boundary

```typescript
// app/error.tsx
"use client";

import { useEffect } from "react";
import * as Sentry from "@sentry/nextjs";
import { Button } from "@/components/ui/button";

export default function ErrorPage({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    Sentry.captureException(error);
  }, [error]);

  return (
    <div className="flex min-h-screen flex-col items-center justify-center">
      <h1 className="text-2xl font-bold mb-4">Bir hata oluştu</h1>
      <p className="text-muted-foreground mb-6">
        Beklenmeyen bir hata meydana geldi. Lütfen tekrar deneyin.
      </p>
      <Button onClick={reset}>Tekrar Dene</Button>
    </div>
  );
}
```

### 22.3 X-Correlation-ID Propagation (Detaylı)

```typescript
// Hata raporlamada correlation ID kullanımı
axiosInstance.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const correlationId = error.config?.headers?.["X-Correlation-ID"] as string;

    if (correlationId) {
      // Sentry'ye ekle — backend logları ile eşleştirme
      Sentry.withScope((scope) => {
        scope.setTag("correlation_id", correlationId);
        scope.setExtra("api_url", error.config?.url);
        scope.setExtra("http_status", error.response?.status);
        scope.setExtra("error_code", (error.response?.data as ErrorResponse)?.code);
        Sentry.captureException(error);
      });

      // Console'da göster (geliştirme)
      if (process.env.NODE_ENV === "development") {
        console.error(
          `[API Error] Correlation ID: ${correlationId}`,
          error.config?.url,
          error.response?.status,
          error.response?.data
        );
      }
    }

    return Promise.reject(error);
  }
);
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §25.2` — CorrelationIdFilter. Her request'e UUID eklenir, MDC ile tüm log'lara yayılır.

---

## 23. Performance Optimizasyonu

### 23.1 Core Web Vitals Hedefleri

| Metrik | Hedef | Ölçüm |
|--------|-------|-------|
| LCP (Largest Contentful Paint) | < 2.5s | Sayfadaki en büyük elementin render süresi |
| FID (First Input Delay) | < 100ms | İlk etkileşime yanıt süresi |
| CLS (Cumulative Layout Shift) | < 0.1 | Görsel kararsızlık skoru |
| TTFB (Time to First Byte) | < 800ms | İlk byte'a kadar geçen süre |

### 23.2 Image Optimization

```typescript
// DOĞRU: next/image kullan
import Image from "next/image";

<Image
  src={service.imageUrl}
  alt={service.name}
  width={400}
  height={300}
  className="rounded-lg object-cover"
  placeholder="blur"
  blurDataURL="data:image/jpeg;base64,/9j/4AAQSk..."
  sizes="(max-width: 768px) 100vw, (max-width: 1200px) 50vw, 33vw"
/>

// YANLIŞ: Ham img tag'ı kullanma
// <img src={service.imageUrl} alt={service.name} />
```

### 23.3 Lazy Loading (Dynamic Import)

Ağır kütüphaneler dynamic import ile yüklenir:

```typescript
// FullCalendar — sadece takvim sayfasında yükle (~200KB)
import dynamic from "next/dynamic";

const AppointmentCalendar = dynamic(
  () => import("@/features/appointments/components/appointment-calendar"),
  {
    loading: () => <div className="h-[600px] animate-pulse bg-muted rounded" />,
    ssr: false, // FullCalendar window nesnesine bağımlı
  }
);

// Tiptap — sadece blog editörde yükle (~150KB)
const RichTextEditor = dynamic(
  () => import("@/components/shared/rich-text-editor"),
  { ssr: false }
);

// Recharts — sadece dashboard'da yükle (~100KB)
const RevenueChart = dynamic(
  () => import("@/features/dashboard/components/revenue-chart"),
  { loading: () => <div className="h-[300px] animate-pulse bg-muted rounded" /> }
);
```

### 23.4 Bundle Analysis

```json
// package.json
{
  "scripts": {
    "analyze": "ANALYZE=true next build"
  }
}
```

```typescript
// next.config.ts
import withBundleAnalyzer from "@next/bundle-analyzer";

const config = withBundleAnalyzer({
  enabled: process.env.ANALYZE === "true",
})({
  // ... next config
});
```

### 23.5 Code Splitting Kuralları

- Her route otomatik olarak ayrı chunk'ta (Next.js App Router)
- Feature modülleri `dynamic()` ile lazy load
- Ağır kütüphaneler (FullCalendar, Tiptap, Recharts) sadece kullanan sayfada
- `use client` directive'i mümkün olduğunca alt component'lerde (Server Components varsayılan)

---

## 24. Docker & Deployment

### 24.1 Dockerfile (Standalone Output)

```dockerfile
# Dockerfile
FROM node:20-alpine AS base

# Dependencies
FROM base AS deps
RUN apk add --no-cache libc6-compat
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN corepack enable pnpm && pnpm install --frozen-lockfile

# Builder
FROM base AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

ENV NEXT_TELEMETRY_DISABLED=1
ENV NODE_ENV=production

RUN corepack enable pnpm && pnpm build

# Runner
FROM base AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

RUN addgroup --system --gid 1001 nodejs
RUN adduser --system --uid 1001 nextjs

COPY --from=builder /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs

EXPOSE 3000
ENV PORT=3000
ENV HOSTNAME="0.0.0.0"

CMD ["node", "server.js"]
```

### 24.2 Docker Compose (Backend ile Birlikte)

```yaml
# docker-compose.yml
version: "3.8"

services:
  # Frontend
  frontend:
    build:
      context: ../aesthetic-clinic-frontend
      dockerfile: Dockerfile
    ports:
      - "3000:3000"
    environment:
      - NEXT_PUBLIC_API_URL=http://backend:8080
      - NEXT_PUBLIC_PLATFORM_DOMAIN=app.com
      - NEXT_PUBLIC_SENTRY_DSN=${SENTRY_DSN_FRONTEND}
    depends_on:
      - backend
    restart: unless-stopped
    networks:
      - app-network

  # Backend
  backend:
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/aesthetic_saas
      - SPRING_DATASOURCE_USERNAME=${DB_USERNAME}
      - SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
      - SPRING_DATA_REDIS_HOST=redis
      - JWT_SECRET=${JWT_SECRET}
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_started
    restart: unless-stopped
    networks:
      - app-network

  # Nginx Reverse Proxy
  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/conf.d:/etc/nginx/conf.d:ro
      - certbot-etc:/etc/letsencrypt:ro
      - certbot-var:/var/lib/letsencrypt:ro
    depends_on:
      - frontend
      - backend
    restart: unless-stopped
    networks:
      - app-network

  # MySQL
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_DATABASE: aesthetic_saas
      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD}
      MYSQL_USER: ${DB_USERNAME}
      MYSQL_PASSWORD: ${DB_PASSWORD}
    volumes:
      - mysql-data:/var/lib/mysql
    command: >
      --character-set-server=utf8mb4
      --collation-server=utf8mb4_turkish_ci
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped
    networks:
      - app-network

  # Redis
  redis:
    image: redis:7-alpine
    volumes:
      - redis-data:/data
    restart: unless-stopped
    networks:
      - app-network

  # Certbot (TLS)
  certbot:
    image: certbot/certbot
    volumes:
      - certbot-etc:/etc/letsencrypt
      - certbot-var:/var/lib/letsencrypt
    entrypoint: "/bin/sh -c 'trap exit TERM; while :; do certbot renew; sleep 12h & wait $${!}; done;'"
    networks:
      - app-network

volumes:
  mysql-data:
  redis-data:
  certbot-etc:
  certbot-var:

networks:
  app-network:
    driver: bridge
```

### 24.3 Nginx Konfigürasyonu

```nginx
# nginx/conf.d/default.conf
# Wildcard subdomain routing

# Rate limiting zone
limit_req_zone $binary_remote_addr zone=api:10m rate=100r/m;

server {
    listen 80;
    server_name *.app.com app.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name *.app.com app.com;

    # TLS
    ssl_certificate /etc/letsencrypt/live/app.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/app.com/privkey.pem;

    # Security headers (Backend §24.2 ile uyumlu)
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Content-Security-Policy "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self' https://api.app.com;" always;

    # Backend API proxy
    location /api/ {
        limit_req zone=api burst=20 nodelay;
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Frontend (Next.js)
    location / {
        proxy_pass http://frontend:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Next.js static files
    location /_next/static/ {
        proxy_pass http://frontend:3000;
        expires 365d;
        add_header Cache-Control "public, immutable";
    }

    # Uploads proxy (max 5MB)
    client_max_body_size 5M;
}
```

### 24.4 Environment Variables

```bash
# .env.example
# ─── Public (Browser'da erişilebilir) ──────────────
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_PLATFORM_DOMAIN=app.com
NEXT_PUBLIC_SENTRY_DSN=
NEXT_PUBLIC_DEV_TENANT_SLUG=demo

# ─── Server-side Only ──────────────────────────────
SENTRY_AUTH_TOKEN=
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §24.1-24.2` — Production Docker Compose, Nginx security headers.

---

## 25. Backend ile Entegrasyon Referansı

### 25.1 API Endpoint Listesi

#### Public Endpoints (`/api/public/**`)

| Method | Endpoint | Açıklama | Frontend Kullanım | Durum |
|--------|----------|----------|-------------------|-------|
| GET | `/api/public/tenant/config` | Tenant config | TenantProvider | Planlandı |
| GET | `/api/public/settings` | Site ayarları (SiteSettingsResponse) | TenantProvider, Header, Footer | **Aktif** |
| GET | `/api/public/services` | Hizmet listesi — Pageable (PagedResponse) | Hizmetler sayfası | **Aktif** |
| GET | `/api/public/services/{slug}` | Hizmet detay (ServiceResponse) | Hizmet detay sayfası | **Aktif** |
| GET | `/api/public/service-categories` | Aktif kategoriler listesi | Hizmetler sayfası filtre | **Aktif** |
| GET | `/api/public/blog` | Blog listesi (Pageable) | Blog sayfası | **Aktif** |
| GET | `/api/public/blog/{slug}` | Blog detay | Blog detay sayfası | **Aktif** |
| GET | `/api/public/gallery` | Galeri (Pageable) | Galeri sayfası | **Aktif** |
| GET | `/api/public/reviews` | Onaylı değerlendirmeler (Pageable) | Değerlendirmeler sayfası | **Aktif** |
| GET | `/api/public/products` | Ürünler (Pageable) | Ürünler sayfası | **Aktif** |
| GET | `/api/public/products/{slug}` | Ürün detay | Ürün detay sayfası | **Aktif** |
| GET | `/api/public/staff` | Personel listesi (aktif) | Randevu personel seçimi | **Aktif** |
| GET | `/api/public/availability/slots` | Müsait slotlar | Slot picker | Planlandı |
| POST | `/api/public/appointments` | Randevu oluştur (guest) | Booking wizard | Planlandı |
| POST | `/api/public/appointments/reserve-slot` | Slot rezerve et | Slot picker | Planlandı |
| POST | `/api/public/contact` | İletişim mesajı | İletişim formu | **Aktif** |

#### Auth Endpoints (`/api/auth/**`)

| Method | Endpoint | Açıklama | Frontend Kullanım |
|--------|----------|----------|-------------------|
| POST | `/api/auth/login` | Giriş | Login form |
| POST | `/api/auth/register` | Kayıt | Register form |
| POST | `/api/auth/refresh` | Token yenile | Axios interceptor |
| POST | `/api/auth/logout` | Çıkış | Logout button |
| POST | `/api/auth/forgot-password` | Şifre sıfırlama isteği | Forgot password form |
| POST | `/api/auth/reset-password` | Şifre sıfırla | Reset password form |
| GET | `/api/auth/me` | Mevcut kullanıcı | AuthProvider |
| POST | `/api/auth/verify-email` | Email doğrula | Verify email page |

#### Admin Endpoints (`/api/admin/**`)

| Method | Endpoint | Açıklama | Durum |
|--------|----------|----------|-------|
| GET | `/api/admin/dashboard/stats` | Dashboard istatistikleri | Planlandı |
| GET/POST | `/api/admin/appointments` | Randevu listesi / oluştur | Planlandı |
| GET/PUT/DELETE | `/api/admin/appointments/{id}` | Randevu CRUD | Planlandı |
| PATCH | `/api/admin/appointments/{id}/status` | Durum güncelle | Planlandı |
| PATCH | `/api/admin/appointments/{id}/reschedule` | Yeniden planla | Planlandı |
| GET/PATCH | `/api/admin/settings` | Site ayarları (GET → SiteSettingsResponse, PATCH → partial update) | **Aktif** |
| POST | `/api/admin/services` | Hizmet oluştur (201 Created → ServiceResponse) | **Aktif** |
| GET | `/api/admin/services` | Hizmet listesi — Pageable (PagedResponse\<ServiceResponse\>) | **Aktif** |
| GET | `/api/admin/services/{id}` | Hizmet detay (ServiceResponse) | **Aktif** |
| PATCH | `/api/admin/services/{id}` | Hizmet güncelle — partial update | **Aktif** |
| DELETE | `/api/admin/services/{id}` | Hizmet sil (204 No Content) | **Aktif** |
| POST | `/api/admin/service-categories` | Kategori oluştur (201 Created → ServiceCategoryResponse) | **Aktif** |
| GET | `/api/admin/service-categories` | Kategori listesi (List\<ServiceCategoryResponse\>) | **Aktif** |
| GET | `/api/admin/service-categories/{id}` | Kategori detay | **Aktif** |
| PATCH | `/api/admin/service-categories/{id}` | Kategori güncelle — partial update | **Aktif** |
| DELETE | `/api/admin/service-categories/{id}` | Kategori sil (204 No Content) | **Aktif** |
| GET | `/api/admin/working-hours` | Tesis çalışma saatleri (List\<WorkingHoursResponse\>) | **Aktif** |
| POST | `/api/admin/working-hours` | Tesis çalışma saatleri set (replace-all) | **Aktif** |
| GET | `/api/admin/working-hours/staff/{id}` | Personel çalışma saatleri | **Aktif** |
| POST | `/api/admin/working-hours/staff/{id}` | Personel çalışma saatleri set | **Aktif** |
| POST | `/api/admin/staff` | Personel oluştur (201) | **Aktif** |
| GET | `/api/admin/staff` | Personel listesi | **Aktif** |
| GET | `/api/admin/staff/{id}` | Personel detay | **Aktif** |
| PATCH | `/api/admin/staff/{id}` | Personel güncelle | **Aktif** |
| PATCH | `/api/admin/staff/{id}/deactivate` | Personel deaktif | **Aktif** |
| PATCH | `/api/admin/staff/{id}/activate` | Personel aktif | **Aktif** |
| GET | `/api/admin/patients` | Müşteri listesi (Pageable) | **Aktif** |
| GET | `/api/admin/patients/{id}` | Müşteri detay (aggregate) | **Aktif** |
| PATCH | `/api/admin/patients/{id}/status` | Müşteri durumu güncelle | **Aktif** |
| POST | `/api/admin/patients/{id}/unblacklist` | Kara listeden çıkar | **Aktif** |
| GET | `/api/admin/patients/{id}/appointments` | Müşteri randevu geçmişi | **Aktif** |
| POST | `/api/admin/blog` | Blog yazısı oluştur (201) | **Aktif** |
| GET | `/api/admin/blog` | Blog listesi (Pageable) | **Aktif** |
| GET | `/api/admin/blog/{id}` | Blog detay | **Aktif** |
| PATCH | `/api/admin/blog/{id}` | Blog güncelle | **Aktif** |
| DELETE | `/api/admin/blog/{id}` | Blog sil (204) | **Aktif** |
| PATCH | `/api/admin/blog/{id}/publish` | Blog yayınla/geri al | **Aktif** |
| POST | `/api/admin/products` | Ürün oluştur (201) | **Aktif** |
| GET | `/api/admin/products` | Ürün listesi (Pageable) | **Aktif** |
| GET | `/api/admin/products/{id}` | Ürün detay | **Aktif** |
| PATCH | `/api/admin/products/{id}` | Ürün güncelle | **Aktif** |
| DELETE | `/api/admin/products/{id}` | Ürün sil (204) | **Aktif** |
| POST | `/api/admin/gallery` | Galeri öğesi oluştur (201) | **Aktif** |
| GET | `/api/admin/gallery` | Galeri listesi (Pageable) | **Aktif** |
| GET | `/api/admin/gallery/{id}` | Galeri detay | **Aktif** |
| PATCH | `/api/admin/gallery/{id}` | Galeri güncelle | **Aktif** |
| DELETE | `/api/admin/gallery/{id}` | Galeri sil (204) | **Aktif** |
| GET | `/api/admin/reviews` | Değerlendirme listesi (Pageable) | **Aktif** |
| PATCH | `/api/admin/reviews/{id}/approve` | Onayla | **Aktif** |
| PATCH | `/api/admin/reviews/{id}/reject` | Reddet | **Aktif** |
| POST | `/api/admin/reviews/{id}/response` | Admin yanıtı | **Aktif** |
| DELETE | `/api/admin/reviews/{id}` | Sil (204) | **Aktif** |
| POST | `/api/admin/notes` | Müşteri notu oluştur (201) | **Aktif** |
| GET | `/api/admin/notes?clientId={id}` | Not listesi (Pageable) | **Aktif** |
| PATCH | `/api/admin/notes/{id}` | Not güncelle | **Aktif** |
| DELETE | `/api/admin/notes/{id}` | Not sil (204) | **Aktif** |
| GET | `/api/admin/patients/{clientId}/records` | Hasta kaydı | **Aktif** |
| PATCH | `/api/admin/patients/{clientId}/records` | Hasta kaydı güncelle | **Aktif** |
| POST | `/api/admin/patients/{clientId}/records/treatments` | Tedavi ekle (201) | **Aktif** |
| GET | `/api/admin/patients/{clientId}/records/treatments` | Tedavi listesi (Pageable) | **Aktif** |
| PATCH | `/api/admin/patients/{clientId}/records/treatments/{id}` | Tedavi güncelle | **Aktif** |
| DELETE | `/api/admin/patients/{clientId}/records/treatments/{id}` | Tedavi sil (204) | **Aktif** |
| GET | `/api/admin/notifications/logs` | Bildirim logları (Pageable) | **Aktif** |
| GET | `/api/admin/notifications/templates` | Bildirim şablonları | **Aktif** |
| PATCH | `/api/admin/notifications/templates/{id}` | Şablon güncelle | **Aktif** |
| GET | `/api/admin/contact-messages` | İletişim mesajları (Pageable) | **Aktif** |
| GET | `/api/admin/contact-messages/{id}` | Mesaj detay | **Aktif** |
| PATCH | `/api/admin/contact-messages/{id}/read` | Okundu işaretle | **Aktif** |
| PATCH | `/api/admin/contact-messages/{id}/unread` | Okunmadı işaretle | **Aktif** |
| GET | `/api/admin/contact-messages/unread-count` | Okunmamış sayısı | **Aktif** |
| DELETE | `/api/admin/contact-messages/{id}` | Mesaj sil (204) | **Aktif** |
| POST | `/api/admin/upload` | Dosya yükleme (201) | **Aktif** |
| GET | `/api/admin/billing` | Abonelik bilgisi | **Aktif** |
| POST | `/api/admin/billing/change-plan` | Plan değiştir | **Aktif** |
| POST | `/api/admin/billing/cancel` | Abonelik iptal | **Aktif** |
| GET | `/api/admin/billing/usage` | Plan kullanım bilgisi | **Aktif** |
| GET | `/api/admin/audit-logs` | Denetim logları (Pageable) | **Aktif** |
| GET | `/api/admin/audit-logs/user/{userId}` | Kullanıcı bazlı log | **Aktif** |
| GET | `/api/admin/seo/pages` | SEO-enabled entity listesi | **Aktif** |
| PATCH | `/api/admin/seo/{entityType}/{entityId}` | SEO alanları güncelle (entityType: blog-post, product, service) | **Aktif** |

#### Client Endpoints (`/api/client/**`)

| Method | Endpoint | Açıklama |
|--------|----------|----------|
| GET | `/api/client/appointments` | Randevularım (Pageable) |
| POST | `/api/client/appointments/{id}/cancel` | Randevu iptal |
| POST | `/api/client/reviews` | Değerlendirme yaz |
| GET | `/api/client/profile` | Profil bilgisi |
| PATCH | `/api/client/profile` | Profil güncelle |
| GET | `/api/client/gdpr/export` | Veri dışa aktarma (KVKK) |
| POST | `/api/client/gdpr/anonymize` | Hesap anonimleştirme (KVKK) |
| GET | `/api/client/gdpr/consents` | Onay kayıtları |
| POST | `/api/client/gdpr/consents` | Onay ver |
| DELETE | `/api/client/gdpr/consents/{type}` | Onay geri al |

#### Platform Admin Endpoints (`/api/platform/**`)

| Method | Endpoint | Açıklama | Durum |
|--------|----------|----------|-------|
| GET | `/api/platform/tenants` | Tüm tenant listesi (Pageable → PagedResponse\<TenantResponse\>) | **Aktif** |
| GET | `/api/platform/tenants/{id}` | Tenant detay (TenantDetailResponse — subscription, staff/client count, review stats) | **Aktif** |
| PATCH | `/api/platform/tenants/{id}/activate` | Tenant aktifleştir | **Aktif** |
| PATCH | `/api/platform/tenants/{id}/deactivate` | Tenant deaktifleştir | **Aktif** |
| GET | `/api/platform/stats` | Platform geneli istatistikler (PlatformStatsResponse) | **Aktif** |

> **Auth:** `PLATFORM_ADMIN` yetkisi gerekir. TenantContext olmadan çalışır.

#### Staff Endpoints (`/api/staff/**`)

| Method | Endpoint | Açıklama |
|--------|----------|----------|
| GET | `/api/staff/calendar` | Takvimim |
| GET | `/api/staff/schedule` | Çalışma saatlerim |
| GET | `/api/staff/appointments/{id}` | Randevu detay (sadece kendi) |

### 25.2 TypeScript Enum Karşılıkları

Tüm backend enum'ları frontend'de TypeScript type olarak tanımlıdır:

```typescript
// lib/constants/enums.ts

// Backend: com.aesthetic.backend.domain.tenant.BusinessType
export const BusinessType = { /* §2.4'te tanımlı */ } as const;

// Backend: com.aesthetic.backend.domain.subscription.FeatureModule
export const FeatureModule = { /* §8.1'de tanımlı */ } as const;

// Backend: com.aesthetic.backend.dto.response.ErrorCode
export const ErrorCode = { /* §5.2'de tanımlı */ } as const;

// Backend: com.aesthetic.backend.domain.user.Role
export const Role = { /* §4.3'te tanımlı */ } as const;

// Backend: com.aesthetic.backend.domain.tenant.SubscriptionPlan
export const SubscriptionPlan = {
  TRIAL: "TRIAL",
  STARTER: "STARTER",
  PROFESSIONAL: "PROFESSIONAL",
  BUSINESS: "BUSINESS",
  ENTERPRISE: "ENTERPRISE",
} as const;

// Backend: AppointmentStatus
export const AppointmentStatus = { /* §11.3'te tanımlı */ } as const;

// Backend: SubscriptionStatus
export const SubscriptionStatus = {
  TRIAL: "TRIAL",
  ACTIVE: "ACTIVE",
  PAST_DUE: "PAST_DUE",
  CANCELLED: "CANCELLED",
  EXPIRED: "EXPIRED",
} as const;

// Backend: BillingPeriod
export const BillingPeriod = {
  MONTHLY: "MONTHLY",
  YEARLY: "YEARLY",
} as const;

// Backend: NotificationType
export const NotificationType = {
  APPOINTMENT_CONFIRMATION: "APPOINTMENT_CONFIRMATION",
  REMINDER_24H: "REMINDER_24H",
  REMINDER_1H: "REMINDER_1H",
  CANCELLED: "CANCELLED",
  RESCHEDULED: "RESCHEDULED",
  NO_SHOW_WARNING: "NO_SHOW_WARNING",
  BLACKLIST: "BLACKLIST",
  WELCOME: "WELCOME",
  PASSWORD_RESET: "PASSWORD_RESET",
  TRIAL_EXPIRING: "TRIAL_EXPIRING",
  SUBSCRIPTION_RENEWED: "SUBSCRIPTION_RENEWED",
} as const;

// Backend: DeliveryStatus
export const DeliveryStatus = {
  PENDING: "PENDING",
  SENT: "SENT",
  FAILED: "FAILED",
  BOUNCED: "BOUNCED",
} as const;

// Backend: ReviewApprovalStatus
export const ReviewApprovalStatus = {
  PENDING: "PENDING",
  APPROVED: "APPROVED",
  REJECTED: "REJECTED",
} as const;

// Backend: ConsentType
export const ConsentType = {
  TERMS_OF_SERVICE: "TERMS_OF_SERVICE",
  PRIVACY_POLICY: "PRIVACY_POLICY",
  MARKETING_EMAIL: "MARKETING_EMAIL",
  MARKETING_SMS: "MARKETING_SMS",
  DATA_PROCESSING: "DATA_PROCESSING",
} as const;
```

### 25.3 DTO Değişiklikleri (Son Güncelleme)

Aşağıdaki DTO'lara yeni alanlar eklendi — frontend TypeScript type'ları güncellenmelidir:

**ProductResponse** — Yeni alan:
- `features: string[]` — Ürün özellikleri listesi

**CreateProductRequest / UpdateProductRequest** — Yeni alan:
- `features: string[]` (create'te default `[]`, update'te optional)

**BlogPostResponse** — Yeni alan:
- `tags: string[]` — Blog yazısı etiketleri

**CreateBlogPostRequest / UpdateBlogPostRequest** — Yeni alan:
- `tags: string[]` (create'te default `[]`, update'te optional)

**GalleryItemResponse** — Yeni alanlar:
- `serviceId: string | null` — İlişkili hizmet ID
- `serviceName: string | null` — İlişkili hizmet adı

**CreateGalleryItemRequest / UpdateGalleryItemRequest** — Yeni alan:
- `serviceId: string | null` (optional)
- `removeService: boolean` (sadece update'te, default `false`)

**ReviewResponse** — Güncellenmiş alanlar:
- `appointmentId: string | null` — İlişkili randevu ID
- `serviceId: string | null` — İlişkili hizmet ID
- `serviceName: string | null` — İlişkili hizmet adı
- `approvalStatus: ReviewApprovalStatus` — Onay durumu (PENDING / APPROVED / REJECTED) — `isApproved: boolean` yerine enum
- `adminResponse: string | null` — Admin yanıtı
- `adminResponseAt: string | null` — Admin yanıt tarihi (ISO 8601)

**CreateReviewRequest** — Güncellenmiş alanlar:
- `appointmentId?: string` — İlişkili randevu (optional)
- `serviceId?: string` — İlişkili hizmet (optional)

**BlogPostResponse** — Yeni alan:
- `readTime: string` — Hesaplanan okuma süresi (örn. "3 dk okuma")

**Yeni DTO'lar:**
- `TenantResponse` — Platform admin tenant listesi
- `TenantDetailResponse` — Platform admin tenant detay (subscription, staff/client count, review stats)
- `PlatformStatsResponse` — Platform geneli istatistikler (totalTenants, activeTenants, totalUsers, totalAppointments, trialTenants, activePlanTenants)
- `SeoResponse` — SEO alanları (entityType, entityId, title, slug, seoTitle, seoDescription, ogImage)
- `UpdateSeoRequest` — SEO güncelleme (seoTitle, seoDescription, ogImage)

---

## 26. Client Portal

### 26.1 Randevularım

```typescript
// features/client-portal/api/use-my-appointments.ts
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";
import { queryKeys } from "@/lib/constants/query-keys";
import type { AppointmentResponse } from "@/features/appointments/types";
import type { PagedData } from "@/lib/api/types";

interface MyAppointmentParams {
  page: number;
  size: number;
  type: "upcoming" | "past";
  status?: string;
}

export function useMyAppointments(params: MyAppointmentParams) {
  return useQuery({
    queryKey: queryKeys.portal.myAppointments(params),
    queryFn: () =>
      apiClient.getPaged<AppointmentResponse>("/api/client/appointments", {
        page: params.page,
        size: params.size,
        type: params.type,
        status: params.status,
      }),
  });
}
```

### 26.2 Randevu İptal (İptal Politikası)

```typescript
// features/client-portal/components/cancel-appointment-dialog.tsx
"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTenant } from "@/lib/providers/tenant-provider";
import { differenceInHours, parseISO } from "date-fns";
import { toast } from "sonner";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { apiClient } from "@/lib/api/client";
import type { AppointmentResponse } from "@/features/appointments/types";

export function CancelAppointmentDialog({
  appointment,
  open,
  onOpenChange,
}: {
  appointment: AppointmentResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const { tenant } = useTenant();
  const queryClient = useQueryClient();
  const cancellationLimit = tenant?.settings?.cancellationHoursLimit ?? 24;

  // İptal süresi kontrolü
  const appointmentDateTime = parseISO(
    `${appointment.date}T${appointment.startTime}`
  );
  const hoursUntilAppointment = differenceInHours(
    appointmentDateTime,
    new Date()
  );
  const canCancel = hoursUntilAppointment >= cancellationLimit;

  const cancelMutation = useMutation({
    mutationFn: () =>
      apiClient.post(`/api/client/appointments/${appointment.id}/cancel`, {}),
    onSuccess: () => {
      toast.success("Randevunuz iptal edildi.");
      queryClient.invalidateQueries({ queryKey: ["portal"] });
      onOpenChange(false);
    },
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Randevu İptali</DialogTitle>
        </DialogHeader>
        {canCancel ? (
          <div className="space-y-4">
            <p>
              <strong>{appointment.date}</strong> tarihli{" "}
              <strong>{appointment.startTime}</strong> randevunuzu iptal etmek
              istediğinize emin misiniz?
            </p>
            <DialogFooter>
              <Button variant="outline" onClick={() => onOpenChange(false)}>
                Vazgeç
              </Button>
              <Button
                variant="destructive"
                onClick={() => cancelMutation.mutate()}
                disabled={cancelMutation.isPending}
              >
                İptal Et
              </Button>
            </DialogFooter>
          </div>
        ) : (
          <div className="space-y-4">
            <p className="text-destructive">
              Randevunuza {cancellationLimit} saatten az kaldığı için online
              iptal yapılamaz. Lütfen işletmeyle iletişime geçin.
            </p>
            <DialogFooter>
              <Button onClick={() => onOpenChange(false)}>Tamam</Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
```

### 26.3 Guest → Registered Eşleştirme UX

```typescript
// features/auth/components/account-linked-banner.tsx
"use client";

import { useQuery } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";
import { useAuth } from "@/lib/providers/auth-provider";
import { Info } from "lucide-react";

/**
 * Kayıt sonrası, daha önce guest olarak alınmış randevuların
 * otomatik bağlanması durumunda bilgilendirme banner'ı gösterir.
 */
export function AccountLinkedBanner() {
  const { user } = useAuth();

  const { data: linkedCount } = useQuery({
    queryKey: ["portal", "linked-appointments"],
    queryFn: () =>
      apiClient.get<{ count: number }>("/api/client/linked-appointments-count"),
    enabled: !!user,
    staleTime: Infinity, // Bir kez kontrol et yeterli
  });

  if (!linkedCount || linkedCount.count === 0) return null;

  return (
    <div className="flex items-center gap-3 rounded-lg border border-blue-200 bg-blue-50 p-4 dark:border-blue-800 dark:bg-blue-950">
      <Info className="h-5 w-5 text-blue-600" />
      <p className="text-sm">
        Daha önce misafir olarak aldığınız{" "}
        <strong>{linkedCount.count} randevu</strong> hesabınıza bağlandı.
      </p>
    </div>
  );
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §28.4` — Guest → Registered matching. Kayıt sonrası email eşleşmesiyle otomatik bağlama.

---

## 27. Staff Portal

### 27.1 Staff Takvimi

```typescript
// app/(staff)/staff/calendar/page.tsx
"use client";

import dynamic from "next/dynamic";
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";
import { format } from "date-fns";
import type { AppointmentResponse } from "@/features/appointments/types";

const FullCalendar = dynamic(() => import("@fullcalendar/react"), {
  ssr: false,
});
import dayGridPlugin from "@fullcalendar/daygrid";
import timeGridPlugin from "@fullcalendar/timegrid";
import trLocale from "@fullcalendar/core/locales/tr";

export default function StaffCalendarPage() {
  const today = format(new Date(), "yyyy-MM-dd");

  const { data: appointments } = useQuery({
    queryKey: ["staff", "calendar", today],
    queryFn: () =>
      apiClient.get<AppointmentResponse[]>("/api/staff/calendar", {
        date: today,
      }),
    staleTime: 60 * 1000,
    refetchInterval: 2 * 60 * 1000, // 2 dk'da bir yenile
  });

  const events = (appointments ?? []).map((apt) => ({
    id: apt.id,
    title: `${apt.clientName} — ${apt.services.map((s) => s.serviceName).join(", ")}`,
    start: `${apt.date}T${apt.startTime}`,
    end: `${apt.date}T${apt.endTime}`,
    backgroundColor: getStatusColor(apt.status),
    borderColor: getStatusColor(apt.status),
  }));

  return (
    <div className="p-6">
      <h1 className="text-2xl font-bold mb-6">Takvimim</h1>
      <FullCalendar
        plugins={[dayGridPlugin, timeGridPlugin]}
        initialView="timeGridWeek"
        locale={trLocale}
        events={events}
        slotMinTime="08:00:00"
        slotMaxTime="20:00:00"
        height="auto"
        headerToolbar={{
          left: "prev,next today",
          center: "title",
          right: "timeGridDay,timeGridWeek",
        }}
      />
    </div>
  );
}

function getStatusColor(status: string): string {
  const colors: Record<string, string> = {
    PENDING: "#f59e0b",
    CONFIRMED: "#3b82f6",
    IN_PROGRESS: "#8b5cf6",
    COMPLETED: "#22c55e",
    CANCELLED: "#ef4444",
    NO_SHOW: "#6b7280",
  };
  return colors[status] ?? "#6b7280";
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §28.8` — STAFF role: sadece kendi randevularını görebilir, read-only kısıtlı erişim.

---

## 28. Ödeme & Abonelik (Frontend)

### 28.1 Plan Seçim UI

```typescript
// features/subscription/components/plan-card.tsx
"use client";

import { Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils/cn";
import type { PlanDetails } from "../types";

interface PlanCardProps {
  plan: PlanDetails;
  isCurrentPlan: boolean;
  onSelect: () => void;
  isLoading?: boolean;
}

export function PlanCard({
  plan,
  isCurrentPlan,
  onSelect,
  isLoading,
}: PlanCardProps) {
  return (
    <Card
      className={cn(
        "relative",
        isCurrentPlan && "border-primary ring-2 ring-primary/20",
        plan.isPopular && "border-primary"
      )}
    >
      {plan.isPopular && (
        <Badge className="absolute -top-3 left-1/2 -translate-x-1/2">
          En Popüler
        </Badge>
      )}

      <CardHeader>
        <CardTitle>{plan.name}</CardTitle>
        <div className="flex items-baseline gap-1">
          <span className="text-3xl font-bold">
            {new Intl.NumberFormat("tr-TR", {
              style: "currency",
              currency: "TRY",
              maximumFractionDigits: 0,
            }).format(plan.monthlyPrice)}
          </span>
          <span className="text-muted-foreground">/ay</span>
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        <ul className="space-y-2">
          {plan.features.map((feature) => (
            <li key={feature} className="flex items-center gap-2 text-sm">
              <Check className="h-4 w-4 text-green-500" />
              {feature}
            </li>
          ))}
        </ul>

        <div className="text-sm text-muted-foreground space-y-1">
          <p>Personel: {plan.staffLimit === -1 ? "Sınırsız" : plan.staffLimit}</p>
          <p>Randevu/ay: {plan.appointmentLimit === -1 ? "Sınırsız" : plan.appointmentLimit}</p>
          <p>Depolama: {plan.storageLimit}</p>
        </div>

        <Button
          onClick={onSelect}
          disabled={isCurrentPlan || isLoading}
          variant={isCurrentPlan ? "outline" : "default"}
          className="w-full"
        >
          {isCurrentPlan ? "Mevcut Plan" : "Planı Seç"}
        </Button>
      </CardContent>
    </Card>
  );
}
```

### 28.2 PAST_DUE Grace Period Bildirimi

```typescript
// components/shared/subscription-warning.tsx
"use client";

import { useQuery } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";
import { queryKeys } from "@/lib/constants/query-keys";
import { AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useRouter } from "next/navigation";

export function SubscriptionWarning() {
  const router = useRouter();
  const { data: subscription } = useQuery({
    queryKey: queryKeys.subscription.current(),
    queryFn: () => apiClient.get("/api/admin/subscription"),
  });

  if (!subscription) return null;

  // PAST_DUE — 7 gün grace period
  if (subscription.status === "PAST_DUE") {
    return (
      <div className="flex items-center gap-3 rounded-lg border border-yellow-200 bg-yellow-50 p-4 dark:border-yellow-800 dark:bg-yellow-950">
        <AlertTriangle className="h-5 w-5 text-yellow-600" />
        <div className="flex-1">
          <p className="text-sm font-medium">Ödeme başarısız</p>
          <p className="text-xs text-muted-foreground">
            Son ödemeniz başarısız oldu. {subscription.gracePeriodEndsAt} tarihine
            kadar ödeme yapılmazsa hesabınız kısıtlanacaktır.
          </p>
        </div>
        <Button
          size="sm"
          onClick={() => router.push("/admin/settings/subscription")}
        >
          Ödeme Yap
        </Button>
      </div>
    );
  }

  // TRIAL — süre bilgisi
  if (subscription.status === "TRIAL" && subscription.trialDaysRemaining <= 3) {
    return (
      <div className="flex items-center gap-3 rounded-lg border border-blue-200 bg-blue-50 p-4 dark:border-blue-800 dark:bg-blue-950">
        <AlertTriangle className="h-5 w-5 text-blue-600" />
        <div className="flex-1">
          <p className="text-sm font-medium">
            Deneme süreniz {subscription.trialDaysRemaining} gün sonra bitiyor
          </p>
        </div>
        <Button
          size="sm"
          onClick={() => router.push("/admin/settings/subscription")}
        >
          Plan Seç
        </Button>
      </div>
    );
  }

  return null;
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §28.5` — Payment Lifecycle: ACTIVE → PAST_DUE (7 gün grace period, 3 retry).

---

## 29. Email Doğrulama

### 29.1 Doğrulama Akışı

```
Kayıt → Email gönderildi sayfası → Email'deki link → /verify-email?token=xxx → Doğrulandı
```

```typescript
// app/(auth)/verify-email/page.tsx
"use client";

import { useEffect, useState } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";
import { CheckCircle, XCircle, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";

export default function VerifyEmailPage() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const token = searchParams.get("token");
  const [status, setStatus] = useState<"loading" | "success" | "error" | "waiting">(
    token ? "loading" : "waiting"
  );

  const verifyMutation = useMutation({
    mutationFn: (token: string) =>
      apiClient.post("/api/auth/verify-email", { token }),
    onSuccess: () => setStatus("success"),
    onError: () => setStatus("error"),
  });

  useEffect(() => {
    if (token) {
      verifyMutation.mutate(token);
    }
  }, [token]);

  if (status === "waiting") {
    return (
      <div className="text-center space-y-4">
        <h1 className="text-2xl font-bold">Email Doğrulama</h1>
        <p className="text-muted-foreground">
          Kayıt e-posta adresinize bir doğrulama bağlantısı gönderdik.
          Lütfen e-postanızı kontrol edin.
        </p>
      </div>
    );
  }

  if (status === "loading") {
    return (
      <div className="flex flex-col items-center gap-4">
        <Loader2 className="h-8 w-8 animate-spin text-primary" />
        <p>Email doğrulanıyor...</p>
      </div>
    );
  }

  if (status === "success") {
    return (
      <div className="text-center space-y-4">
        <CheckCircle className="h-16 w-16 text-green-500 mx-auto" />
        <h1 className="text-2xl font-bold">Email Doğrulandı!</h1>
        <p className="text-muted-foreground">
          Hesabınız başarıyla doğrulandı. Şimdi giriş yapabilirsiniz.
        </p>
        <Button onClick={() => router.push("/login")}>Giriş Yap</Button>
      </div>
    );
  }

  return (
    <div className="text-center space-y-4">
      <XCircle className="h-16 w-16 text-destructive mx-auto" />
      <h1 className="text-2xl font-bold">Doğrulama Başarısız</h1>
      <p className="text-muted-foreground">
        Doğrulama bağlantısı geçersiz veya süresi dolmuş olabilir.
      </p>
      <Button variant="outline" onClick={() => router.push("/login")}>
        Giriş Sayfasına Dön
      </Button>
    </div>
  );
}
```

### 29.2 Email Doğrulanmadı Banner

```typescript
// components/shared/email-verification-banner.tsx
"use client";

import { useAuth } from "@/lib/providers/auth-provider";
import { useMutation } from "@tanstack/react-query";
import { apiClient } from "@/lib/api/client";
import { toast } from "sonner";
import { AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";

export function EmailVerificationBanner() {
  const { user } = useAuth();

  const resendMutation = useMutation({
    mutationFn: () => apiClient.post("/api/auth/resend-verification", {}),
    onSuccess: () => toast.success("Doğrulama e-postası yeniden gönderildi."),
  });

  if (!user || user.isEmailVerified) return null;

  return (
    <div className="flex items-center gap-3 rounded-lg border border-yellow-200 bg-yellow-50 p-4 dark:border-yellow-800 dark:bg-yellow-950">
      <AlertTriangle className="h-5 w-5 text-yellow-600" />
      <div className="flex-1">
        <p className="text-sm font-medium">E-posta adresiniz doğrulanmadı</p>
        <p className="text-xs text-muted-foreground">
          Bazı özellikler kısıtlı olabilir.
        </p>
      </div>
      <Button
        size="sm"
        variant="outline"
        onClick={() => resendMutation.mutate()}
        disabled={resendMutation.isPending}
      >
        Tekrar Gönder
      </Button>
    </div>
  );
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §28.10/S5` — EmailVerificationService.

---

## 30. Onboarding Wizard

### 30.1 Yeni Tenant Kurulum Akışı

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ 1. İşletme   │──►│ 2. Plan      │──►│ 3. Ayarlar   │──►│ 4. Bildirim  │──►│ 5. Tamamlandı│
│    Tipi      │    │    Seçimi    │    │              │    │    Şablonları│    │              │
│              │    │              │    │ Timezone     │    │              │    │ Dashboard'a  │
│ BusinessType │    │ TRIAL free   │    │ İptal pol.   │    │ 11 template  │    │ yönlendir    │
│ seçimi       │    │ veya plan    │    │ Auto-confirm │    │ önizleme     │    │              │
└──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
```

```typescript
// features/onboarding/components/onboarding-wizard.tsx
"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";
import { apiClient } from "@/lib/api/client";
import { BusinessTypeStep } from "./steps/business-type-step";
import { PlanStep } from "./steps/plan-step";
import { SettingsStep } from "./steps/settings-step";
import { NotificationTemplateStep } from "./steps/notification-template-step";
import { CompletionStep } from "./steps/completion-step";

const STEPS = [
  { title: "İşletme Tipi", component: BusinessTypeStep },
  { title: "Plan Seçimi", component: PlanStep },
  { title: "Ayarlar", component: SettingsStep },
  { title: "Bildirim Şablonları", component: NotificationTemplateStep },
  { title: "Tamamlandı", component: CompletionStep },
];

export function OnboardingWizard() {
  const [step, setStep] = useState(0);
  const [data, setData] = useState<OnboardingData>({});
  const router = useRouter();

  const completeMutation = useMutation({
    mutationFn: (onboardingData: OnboardingData) =>
      apiClient.post("/api/admin/onboarding/complete", onboardingData),
    onSuccess: () => {
      toast.success("Kurulum tamamlandı! Dashboard'a yönlendiriliyorsunuz.");
      router.push("/admin");
    },
  });

  const CurrentStep = STEPS[step].component;

  const handleNext = (stepData: Partial<OnboardingData>) => {
    const newData = { ...data, ...stepData };
    setData(newData);

    if (step === STEPS.length - 2) {
      // Son adımdan önce — tamamla
      completeMutation.mutate(newData);
    }

    setStep((prev) => Math.min(prev + 1, STEPS.length - 1));
  };

  const handleBack = () => {
    setStep((prev) => Math.max(prev - 1, 0));
  };

  return (
    <div className="mx-auto max-w-2xl py-8">
      {/* Progress bar */}
      <div className="mb-8">
        <div className="flex justify-between mb-2">
          {STEPS.map((s, i) => (
            <span
              key={i}
              className={cn(
                "text-xs",
                i <= step ? "text-primary font-medium" : "text-muted-foreground"
              )}
            >
              {s.title}
            </span>
          ))}
        </div>
        <div className="h-2 bg-muted rounded-full">
          <div
            className="h-2 bg-primary rounded-full transition-all"
            style={{ width: `${((step + 1) / STEPS.length) * 100}%` }}
          />
        </div>
      </div>

      {/* Step content */}
      <CurrentStep
        data={data}
        onNext={handleNext}
        onBack={handleBack}
        isFirst={step === 0}
        isLast={step === STEPS.length - 1}
      />
    </div>
  );
}

interface OnboardingData {
  businessType?: string;
  planId?: string;
  timezone?: string;
  cancellationHoursLimit?: number;
  autoConfirmAppointments?: boolean;
  notificationTemplateOverrides?: Record<string, string>;
}
```

> **Backend referans:** `BACKEND_ARCHITECTURE.md §12` — Tenant Onboarding. `§28.6` — 11 default notification template. `§28.7` — SiteSettings.autoConfirmAppointments.


