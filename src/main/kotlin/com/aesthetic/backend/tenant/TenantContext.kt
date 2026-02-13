package com.aesthetic.backend.tenant

import com.aesthetic.backend.exception.TenantNotFoundException

object TenantContext {
    private val currentTenant = ThreadLocal<String>()

    fun setTenantId(tenantId: String) = currentTenant.set(tenantId)

    fun getTenantId(): String = currentTenant.get()
        ?: throw TenantNotFoundException("Tenant context bulunamadı")

    fun getTenantIdOrNull(): String? = currentTenant.get()

    fun clear() = currentTenant.remove()
}
