package com.aesthetic.backend.tenant

interface TenantCacheManager {
    fun evictAllForCurrentTenant(cacheName: String)
}
