package com.aesthetic.backend.security

import com.aesthetic.backend.domain.user.Role
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

class UserPrincipal(
    val id: String,
    val email: String,
    val tenantId: String,
    val role: Role,
    private val passwordHash: String,
    private val isLocked: Boolean = false,
    private val isActive: Boolean = true
) : UserDetails {

    override fun getAuthorities(): Collection<GrantedAuthority> =
        listOf(SimpleGrantedAuthority(role.name))

    override fun getPassword(): String = passwordHash

    override fun getUsername(): String = email

    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean = !isLocked

    override fun isCredentialsNonExpired(): Boolean = true

    override fun isEnabled(): Boolean = isActive
}
