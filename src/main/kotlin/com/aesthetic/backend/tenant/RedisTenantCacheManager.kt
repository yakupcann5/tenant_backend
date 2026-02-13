package com.aesthetic.backend.tenant

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
@Profile("prod")
class RedisTenantCacheManager(
    private val redisTemplate: StringRedisTemplate
) : TenantCacheManager {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun evictAllForCurrentTenant(cacheName: String) {
        val tenantId = TenantContext.getTenantId()
        val pattern = "*tenant:$tenantId:$cacheName:*"
        val keys = scanKeys(pattern)
        if (keys.isNotEmpty()) {
            redisTemplate.delete(keys)
            logger.info("Redis cache evicted {} keys for tenant {} cacheName {}", keys.size, tenantId, cacheName)
        }
    }

    private fun scanKeys(pattern: String): Set<String> {
        val keys = mutableSetOf<String>()
        val scanOptions = ScanOptions.scanOptions().match(pattern).count(100).build()
        redisTemplate.execute { connection ->
            val cursor = connection.keyCommands().scan(scanOptions)
            while (cursor.hasNext()) {
                keys.add(String(cursor.next()))
            }
            null
        }
        return keys
    }
}
