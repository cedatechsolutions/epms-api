package com.cems.api.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates high-entropy opaque tokens (refresh + password-reset) and hashes them for
 * storage. Only the hash is persisted; the raw token is shown to the client once.
 *
 * <p>SHA-256 (not bcrypt) is used deliberately: these tokens are 256-bit random values,
 * so a fast cryptographic digest gives constant-time lookup without a brute-force risk.
 */
@Component
public class TokenHasher {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();

    /** A new 256-bit URL-safe random token to hand to the client. */
    public String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return urlEncoder.encodeToString(bytes);
    }

    /** Deterministic SHA-256 hex hash used as the stored/lookup value. */
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable.", ex);
        }
    }
}
