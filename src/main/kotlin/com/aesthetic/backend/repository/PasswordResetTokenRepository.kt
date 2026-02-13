package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.auth.PasswordResetToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PasswordResetTokenRepository : JpaRepository<PasswordResetToken, String> {
    fun findByTokenAndIsUsedFalse(token: String): PasswordResetToken?

    @Modifying
    @Query("UPDATE PasswordResetToken p SET p.isUsed = true WHERE p.token = :token")
    fun markUsed(@Param("token") token: String)

    @Modifying
    @Query("DELETE FROM PasswordResetToken p WHERE p.expiresAt < :now")
    fun deleteExpired(@Param("now") now: java.time.Instant)
}
