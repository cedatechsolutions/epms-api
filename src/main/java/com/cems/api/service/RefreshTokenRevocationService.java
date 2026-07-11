package com.cems.api.service;

import com.cems.api.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Revokes refresh tokens in an independent transaction. Used for theft detection during
 * rotation: when a revoked token is replayed, the whole family must be revoked and the
 * revocation must persist even though the surrounding request then fails with 401
 * (which would otherwise roll the revocation back). Kept in a separate bean so the
 * {@code REQUIRES_NEW} propagation is honored via the Spring proxy (self-invocation is not).
 */
@Service
public class RefreshTokenRevocationService {

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenRevocationService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllForUser(String userId) {
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
    }
}
