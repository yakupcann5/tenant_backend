package com.aesthetic.backend.dto.request

data class UpdateClientStatusRequest(
    val isBlacklisted: Boolean,
    val blacklistReason: String? = null
)
