package com.aesthetic.backend.tenant

import org.springframework.core.task.TaskDecorator
import org.springframework.security.core.context.SecurityContextHolder

class TenantAwareTaskDecorator : TaskDecorator {

    override fun decorate(runnable: Runnable): Runnable {
        val tenantId = TenantContext.getTenantIdOrNull()
        val clonedContext = SecurityContextHolder.createEmptyContext().apply {
            authentication = SecurityContextHolder.getContext().authentication
        }

        return Runnable {
            try {
                tenantId?.let { TenantContext.setTenantId(it) }
                SecurityContextHolder.setContext(clonedContext)
                runnable.run()
            } finally {
                TenantContext.clear()
                SecurityContextHolder.clearContext()
            }
        }
    }
}
