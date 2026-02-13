package com.aesthetic.backend.dto.request

import jakarta.validation.constraints.Size

data class UpdateStaffRequest(
    @field:Size(max = 100)
    val firstName: String? = null,

    @field:Size(max = 100)
    val lastName: String? = null,

    val phone: String? = null,

    @field:Size(max = 100)
    val title: String? = null,

    val image: String? = null
)
