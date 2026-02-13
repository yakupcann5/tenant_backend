package com.aesthetic.backend.config

import com.aesthetic.backend.tenant.TenantContext
import com.aesthetic.backend.usecase.ModuleAccessService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

@Component
class ModuleGuardInterceptor(
    private val moduleAccessService: ModuleAccessService
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) return true

        val annotation = handler.getMethodAnnotation(RequiresModule::class.java)
            ?: handler.beanType.getAnnotation(RequiresModule::class.java)
            ?: return true

        val tenantId = TenantContext.getTenantIdOrNull() ?: return true

        moduleAccessService.requireAccess(tenantId, annotation.value)
        return true
    }
}
