package com.aesthetic.backend.config

import com.aesthetic.backend.tenant.TenantAwareTaskDecorator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.retry.annotation.EnableRetry
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableAsync
@EnableScheduling
@EnableRetry
class AsyncConfig {

    @Bean("taskExecutor")
    fun taskExecutor(): TaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 5
        executor.maxPoolSize = 20
        executor.queueCapacity = 100
        executor.setThreadNamePrefix("async-")
        executor.setTaskDecorator(TenantAwareTaskDecorator())
        executor.initialize()
        return executor
    }
}
