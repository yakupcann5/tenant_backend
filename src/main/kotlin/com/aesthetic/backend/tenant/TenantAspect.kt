package com.aesthetic.backend.tenant

import jakarta.persistence.EntityManager
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.hibernate.Session
import org.springframework.stereotype.Component

@Aspect
@Component
class TenantAspect(private val entityManager: EntityManager) {

    @Before(
        "execution(* com.aesthetic.backend.repository..*.*(..)) " +
            "&& !execution(* com.aesthetic.backend.repository.TenantRepository.*(..)) " +
            "&& !execution(* com.aesthetic.backend.repository.RefreshTokenRepository.*(..)) " +
            "&& !execution(* com.aesthetic.backend.repository.AuditLogRepository.*(..)) " +
            "&& !execution(* com.aesthetic.backend.repository.ProcessedWebhookEventRepository.*(..))"
    )
    fun enableTenantFilter() {
        val tenantId = TenantContext.getTenantIdOrNull() ?: return
        val session = entityManager.unwrap(Session::class.java)
        session.enableFilter("tenantFilter")
            .setParameter("tenantId", tenantId)
    }
}
