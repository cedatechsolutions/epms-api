package com.cems.api.service;

import com.cems.api.dto.AuthResponse;
import com.cems.api.dto.TokenPair;
import com.cems.api.entity.PasswordReset;
import com.cems.api.entity.RefreshToken;
import com.cems.api.entity.User;
import com.cems.api.repository.PasswordResetRepository;
import com.cems.api.repository.RefreshTokenRepository;
import com.cems.api.repository.UserRepository;
import com.cems.api.security.JwtUtils;
import com.cems.api.security.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;

/**
 * Authentication + credential lifecycle (spec Module 1 §4): password login with account
 * lockout, rotating refresh tokens, logout revocation, forgot/reset, and self-service
 * password change. The access token is a short-lived JWT; the refresh token is an opaque
 * random value stored only as a hash.
 */
@Service
public class AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenRevocationService refreshTokenRevocationService;
    private final PasswordResetRepository passwordResetRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final TokenHasher tokenHasher;
    private final EmailService emailService;
    private final LoginAttemptService loginAttemptService;
    private final ActivityLogService activityLogService;

    private final long passwordResetMinutes;
    private final long refreshExpirationMs;
    private final String resetPasswordUrl;

    public AuthService(AuthenticationManager authenticationManager,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenRevocationService refreshTokenRevocationService,
            PasswordResetRepository passwordResetRepository,
            PasswordEncoder passwordEncoder,
            JwtUtils jwtUtils,
            TokenHasher tokenHasher,
            EmailService emailService,
            LoginAttemptService loginAttemptService,
            ActivityLogService activityLogService,
            @Value("${app.auth.password-reset-minutes:60}") long passwordResetMinutes,
            @Value("${jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs,
            @Value("${app.frontend.reset-password-url:http://localhost:5173/reset-password}") String resetPasswordUrl) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenRevocationService = refreshTokenRevocationService;
        this.passwordResetRepository = passwordResetRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.tokenHasher = tokenHasher;
        this.emailService = emailService;
        this.loginAttemptService = loginAttemptService;
        this.activityLogService = activityLogService;
        this.passwordResetMinutes = passwordResetMinutes;
        this.refreshExpirationMs = refreshExpirationMs;
        this.resetPasswordUrl = resetPasswordUrl;
    }

    /**
     * Authenticates a user, enforcing lockout. Throws {@link LockedException} (→ 423) when
     * locked, {@link BadCredentialsException} (→ 401) on wrong credentials, and Spring's
     * {@code DisabledException} (→ 403) for deactivated accounts.
     */
    @Transactional
    public AuthResponse login(String email, String rawPassword) {
        Instant now = Instant.now();
        User user = userRepository.findByEmail(email).orElse(null);

        if (user != null && isLocked(user, now)) {
            throw lockedException(now, user.getLockedUntil());
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, rawPassword));
        } catch (BadCredentialsException ex) {
            if (user != null) {
                Instant lockedUntil = loginAttemptService.registerFailedAttempt(user.getId());
                if (lockedUntil != null) {
                    throw lockedException(now, lockedUntil);
                }
            }
            throw ex;
        }

        // Authentication succeeded, so the account exists; guard defensively regardless.
        if (user == null) {
            throw unauthorized("Invalid email or password.");
        }

        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        userRepository.save(user);

        activityLogService.record(user.getId(), "auth.login", "user", user.getId(), null);

        TokenPair tokens = issueTokens(user, now);
        return AuthResponse.fromUser(user, tokens.accessToken(), tokens.refreshToken(), TOKEN_TYPE);
    }

    /** Rotates a refresh token: revokes the presented token and issues a fresh pair. */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        Instant now = Instant.now();
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHasher.hash(rawRefreshToken))
                .orElseThrow(() -> unauthorized("Invalid refresh token."));

        if (token.getRevokedAt() != null) {
            // Reuse of an already-rotated token suggests theft — revoke the whole family.
            // Runs in its own transaction so it persists even though we then fail with 401.
            refreshTokenRevocationService.revokeAllForUser(token.getUserId());
            throw unauthorized("Refresh token has been revoked.");
        }
        if (!token.getExpiresAt().isAfter(now)) {
            throw unauthorized("Refresh token has expired.");
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> unauthorized("Account no longer exists."));
        if (!user.isActive() || user.getDeletedAt() != null) {
            throw unauthorized("Account is not active.");
        }

        token.setRevokedAt(now);
        refreshTokenRepository.save(token);

        TokenPair tokens = issueTokens(user, now);
        return AuthResponse.fromUser(user, tokens.accessToken(), tokens.refreshToken(), TOKEN_TYPE);
    }

    /** Logout revokes every active refresh token for the user. */
    @Transactional
    public void logout(String email) {
        userRepository.findByEmail(email)
                .ifPresent(user -> refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now()));
    }

    /**
     * Issues a single-use reset token (valid {@code passwordResetMinutes}) and emails the link.
     * Always returns normally — an unknown/inactive email is silently ignored to avoid
     * account enumeration.
     */
    @Transactional
    public void forgotPassword(String email) {
        Instant now = Instant.now();
        userRepository.findByEmail(email).ifPresent(user -> {
            if (!user.isActive() || user.getDeletedAt() != null) {
                return;
            }
            passwordResetRepository.invalidateOutstandingForUser(user.getId(), now);

            String rawToken = tokenHasher.generateRawToken();
            PasswordReset reset = new PasswordReset();
            reset.setUserId(user.getId());
            reset.setTokenHash(tokenHasher.hash(rawToken));
            reset.setExpiresAt(now.plus(passwordResetMinutes, ChronoUnit.MINUTES));
            passwordResetRepository.save(reset);

            emailService.sendPasswordResetEmail(user.getEmail(), resetPasswordUrl + "?token=" + rawToken);
        });
    }

    /** Consumes a reset token, sets the new password, and revokes all existing sessions. */
    @Transactional
    public void resetPassword(String rawToken, String newPassword, String confirmation) {
        if (!newPassword.equals(confirmation)) {
            throw new IllegalArgumentException("Password confirmation must match password.");
        }
        Instant now = Instant.now();
        PasswordReset reset = passwordResetRepository.findByTokenHash(tokenHasher.hash(rawToken))
                .orElseThrow(() -> unauthorized("Invalid or expired reset token."));
        if (!reset.isRedeemable(now)) {
            throw unauthorized("Invalid or expired reset token.");
        }

        User user = userRepository.findById(reset.getUserId())
                .orElseThrow(() -> unauthorized("Account no longer exists."));

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        reset.setUsedAt(now);
        passwordResetRepository.save(reset);

        refreshTokenRepository.revokeAllForUser(user.getId(), now);
    }

    /** Authenticated self-service password change (also clears the first-login flag). */
    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword, String confirmation) {
        if (!newPassword.equals(confirmation)) {
            throw new IllegalArgumentException("Password confirmation must match password.");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("Authenticated user not found."));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw unauthorized("Current password is incorrect.");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    private TokenPair issueTokens(User user, Instant now) {
        String accessToken = jwtUtils.generateTokenFromUsername(user.getEmail());

        String rawRefresh = tokenHasher.generateRawToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(tokenHasher.hash(rawRefresh));
        refreshToken.setExpiresAt(now.plusMillis(refreshExpirationMs));
        refreshTokenRepository.save(refreshToken);

        return new TokenPair(accessToken, rawRefresh);
    }

    private boolean isLocked(User user, Instant now) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
    }

    private LockedException lockedException(Instant now, Instant lockedUntil) {
        long minutesLeft = Math.max(1, Duration.between(now, lockedUntil).toMinutes() + 1);
        return new LockedException(
                "Account locked due to too many failed attempts. Try again in " + minutesLeft + " minute(s).");
    }

    private ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }
}
