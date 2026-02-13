package com.aesthetic.backend.tenant

import org.slf4j.LoggerFactory
import org.springframework.cache.CacheManager
import org.springframework.cache.caffeine.CaffeineCache
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("dev")
class CaffeineTenantCacheManager(
    private val cacheManager: CacheManager
) : TenantCacheManager {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun evictAllForCurrentTenant(cacheName: String) {
        val tenantId = TenantContext.getTenantId()
        val cache = cacheManager.getCache(cacheName) ?: return
        val nativeCache = (cache as CaffeineCache).nativeCache
        val prefix = "tenant:$tenantId:"
        nativeCache.asMap().keys
            .filter { (it as? String)?.startsWith(prefix) == true }
            .forEach { nativeCache.invalidate(it) }
        logger.debug("Caffeine cache evicted for tenant {} cacheName {}", tenantId, cacheName)
    }
}
