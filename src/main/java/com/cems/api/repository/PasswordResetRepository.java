package com.cems.api.repository;

import com.cems.api.entity.PasswordReset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface PasswordResetRepository extends JpaRepository<PasswordReset, String> {

    Optional<PasswordReset> findByTokenHash(String tokenHash);

    /** Invalidates any outstanding reset tokens for a user before issuing a new one. */
    @Modifying
    @Query("UPDATE PasswordReset r SET r.usedAt = :now "
            + "WHERE r.userId = :userId AND r.usedAt IS NULL")
    int invalidateOutstandingForUser(@Param("userId") String userId, @Param("now") Instant now);
}
