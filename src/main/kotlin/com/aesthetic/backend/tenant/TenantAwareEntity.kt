package com.aesthetic.backend.tenant

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.Filter
import org.hibernate.annotations.FilterDef
import org.hibernate.annotations.ParamDef

@FilterDef(
    name = "tenantFilter",
    parameters = [ParamDef(name = "tenantId", type = String::class)]
)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@EntityListeners(TenantEntityListener::class)
@MappedSuperclass
abstract class TenantAwareEntity {
    @Column(name = "tenant_id", nullable = false, updatable = false)
    lateinit var tenantId: String
}
