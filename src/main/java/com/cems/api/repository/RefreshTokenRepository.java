package com.cems.api.repository;

import com.cems.api.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Revokes every still-active token for a user (logout / global revoke). */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") String userId, @Param("now") Instant now);
}
