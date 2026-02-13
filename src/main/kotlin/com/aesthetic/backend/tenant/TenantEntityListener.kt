package com.aesthetic.backend.tenant

import jakarta.persistence.PrePersist
import jakarta.persistence.PreRemove
import jakarta.persistence.PreUpdate

class TenantEntityListener {

    @PrePersist
    fun onPrePersist(entity: Any) {
        if (entity is TenantAwareEntity) {
            val tenantId = TenantContext.getTenantIdOrNull() ?: return // Platform admin bypass
            try {
                val existingTenantId = entity.tenantId
                if (existingTenantId != tenantId) {
                    throw SecurityException(
                        "Başka tenant'a veri yazma girişimi engellendi! " +
                            "Beklenen: $tenantId, Gelen: $existingTenantId"
                    )
                }
            } catch (_: UninitializedPropertyAccessException) {
                entity.tenantId = tenantId
            }
        }
    }

    @PreUpdate
    fun onPreUpdate(entity: Any) {
        if (entity is TenantAwareEntity) {
            val tenantId = TenantContext.getTenantIdOrNull() ?: return // Platform admin bypass
            if (entity.tenantId != tenantId) {
                throw SecurityException(
                    "Başka tenant'ın verisini güncelleme girişimi engellendi!"
                )
            }
        }
    }

    @PreRemove
    fun onPreRemove(entity: Any) {
        if (entity is TenantAwareEntity) {
            val tenantId = TenantContext.getTenantIdOrNull() ?: return // Platform admin bypass
            if (entity.tenantId != tenantId) {
                throw SecurityException(
                    "Başka tenant'ın verisini silme girişimi engellendi!"
                )
            }
        }
    }
}
