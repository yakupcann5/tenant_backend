package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.Size

data class UpdateProfileRequest(
    @field:Size(max = 100)
    val firstName: String? = null,

    @field:Size(max = 100)
    val lastName: String? = null,

    @field:Size(max = 20)
    val phone: String? = null,

    val image: String? = null
)
