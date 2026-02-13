package com.aesthetic.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "iyzico")
data class IyzicoProperties(
    val apiKey: String = "",
    val secretKey: String = "",
    val baseUrl: String = "https://sandbox-api.iyzipay.com"
)
