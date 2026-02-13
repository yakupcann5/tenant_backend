package com.aesthetic.backend.tenant

import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.stereotype.Component
import java.lang.reflect.Method

@Component("tenantCacheKeyGenerator")
class TenantAwareCacheKeyGenerator : KeyGenerator {

    override fun generate(target: Any, method: Method, vararg params: Any?): Any {
        val tenantId = TenantContext.getTenantIdOrNull() ?: "global"
        val key = if (params.isEmpty()) "" else params.joinToString("::") { it?.toString() ?: "_null_" }
        return "tenant:$tenantId:${method.name}:$key"
    }
}
