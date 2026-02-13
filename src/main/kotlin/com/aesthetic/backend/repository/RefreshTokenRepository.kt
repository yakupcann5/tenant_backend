package com.aesthetic.backend.repository

import com.aesthetic.backend.domain.auth.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RefreshTokenRepository : JpaRepository<RefreshToken, String> {
    fun findByIdAndIsRevokedFalse(id: String): RefreshToken?

    @Modifying
    @Query("UPDATE RefreshToken r SET r.isRevoked = true WHERE r.family = :family AND r.isRevoked = false")
    fun revokeByFamily(@Param("family") family: String): Int

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: String)

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :now")
    fun deleteExpired(@Param("now") now: java.time.Instant)
}
