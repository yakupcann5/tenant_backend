package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, String> {
    fun findByEmailAndTenantId(email: String, tenantId: String): User?
    fun findByTenantIdAndRoleIn(tenantId: String, roles: List<Role>): List<User>
    fun countByTenantIdAndRole(tenantId: String, role: Role): Long
    fun findByTenantIdAndRoleInAndIsActiveTrue(tenantId: String, roles: List<Role>): List<User>
    fun findFirstByTenantIdAndRole(tenantId: String, role: Role): User?
    fun findAllByTenantIdAndRole(tenantId: String, role: Role, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<User>
}
