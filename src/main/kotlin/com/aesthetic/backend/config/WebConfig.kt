package com.aesthetic.backend.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val moduleGuardInterceptor: ModuleGuardInterceptor
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns(
                "https://*.app.com",
                "http://localhost:3000"
            )
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600)
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(moduleGuardInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/api/auth/**",
                "/api/platform/**",
                "/api/webhooks/**"
            )
    }
}
