package com.cems.api.service;

import com.cems.api.entity.User;
import com.cems.api.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Tracks failed-login attempts and enforces lockout (spec Module 1 §4: 5 failures →
 * locked 15 minutes).
 *
 * <p>Runs each increment in its own transaction ({@code REQUIRES_NEW}) so the counter
 * persists even though the caller then throws {@code BadCredentialsException}/
 * {@code LockedException} to fail the request — a normal transaction would roll the
 * increment back with the failed login. Kept in a separate bean so the propagation is
 * honored through the Spring proxy (self-invocation would bypass it).
 */
@Service
public class LoginAttemptService {

    private final UserRepository userRepository;
    private final int maxFailedAttempts;
    private final long lockoutMinutes;

    public LoginAttemptService(UserRepository userRepository,
            @Value("${app.auth.max-failed-attempts:5}") int maxFailedAttempts,
            @Value("${app.auth.lockout-minutes:15}") long lockoutMinutes) {
        this.userRepository = userRepository;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutMinutes = lockoutMinutes;
    }

    /**
     * Records a failed attempt for the user. When the threshold is reached the account is
     * locked and the counter reset.
     *
     * @return the lock expiry instant if this attempt triggered a lock, otherwise {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Instant registerFailedAttempt(String userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }

        int attempts = user.getFailedAttempts() + 1;
        if (attempts >= maxFailedAttempts) {
            Instant lockedUntil = Instant.now().plus(lockoutMinutes, ChronoUnit.MINUTES);
            user.setLockedUntil(lockedUntil);
            user.setFailedAttempts(0);
            userRepository.save(user);
            return lockedUntil;
        }

        user.setFailedAttempts(attempts);
        userRepository.save(user);
        return null;
    }
}
