package com.aesthetic.backend.security

import com.aesthetic.backend.domain.user.Role
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UserPrincipalTest {

    @Test
    fun `should map role to granted authority`() {
        val principal = UserPrincipal(
            id = "user-1",
            email = "admin@test.com",
            tenantId = "tenant-1",
            role = Role.TENANT_ADMIN,
            passwordHash = "encoded"
        )

        assertEquals(1, principal.authorities.size)
        assertTrue(principal.authorities.any { it.authority == "TENANT_ADMIN" })
    }

    @Test
    fun `should report locked account`() {
        val principal = UserPrincipal(
            id = "user-1",
            email = "locked@test.com",
            tenantId = "tenant-1",
            role = Role.CLIENT,
            passwordHash = "encoded",
            isLocked = true,
            isActive = true
        )

        assertFalse(principal.isAccountNonLocked)
        assertTrue(principal.isEnabled)
    }

    @Test
    fun `should report inactive account`() {
        val principal = UserPrincipal(
            id = "user-1",
            email = "inactive@test.com",
            tenantId = "tenant-1",
            role = Role.STAFF,
            passwordHash = "encoded",
            isLocked = false,
            isActive = false
        )

        assertTrue(principal.isAccountNonLocked)
        assertFalse(principal.isEnabled)
    }

    @Test
    fun `should return correct user details`() {
        val principal = UserPrincipal(
            id = "user-1",
            email = "user@test.com",
            tenantId = "tenant-1",
            role = Role.STAFF,
            passwordHash = "hashedPassword"
        )

        assertEquals("user@test.com", principal.username)
        assertEquals("hashedPassword", principal.password)
        assertTrue(principal.isAccountNonExpired)
        assertTrue(principal.isCredentialsNonExpired)
        assertTrue(principal.isAccountNonLocked)
        assertTrue(principal.isEnabled)
    }

    @Test
    fun `each role should map to single authority`() {
        Role.entries.forEach { role ->
            val principal = UserPrincipal("id", "email", "tenant", role, "pass")
            assertEquals(1, principal.authorities.size)
            assertEquals(role.name, principal.authorities.first().authority)
        }
    }

    @Test
    fun `default principal should be active and unlocked`() {
        val principal = UserPrincipal(
            id = "user-1",
            email = "user@test.com",
            tenantId = "tenant-1",
            role = Role.CLIENT,
            passwordHash = ""
        )

        assertTrue(principal.isAccountNonLocked)
        assertTrue(principal.isEnabled)
        assertTrue(principal.isAccountNonExpired)
        assertTrue(principal.isCredentialsNonExpired)
    }
}
