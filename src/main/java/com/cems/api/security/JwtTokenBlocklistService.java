package com.cems.api.security;

import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JwtTokenBlocklistService {

    private final Map<String, Date> revokedTokens = new ConcurrentHashMap<>();

    public void revoke(String token, Date expiresAt) {
        purgeExpiredTokens();
        revokedTokens.put(token, expiresAt);
    }

    public boolean isRevoked(String token) {
        purgeExpiredTokens();
        Date expiresAt = revokedTokens.get(token);
        if (expiresAt == null) {
            return false;
        }

        if (expiresAt.before(new Date())) {
            revokedTokens.remove(token);
            return false;
        }

        return true;
    }

    private void purgeExpiredTokens() {
        Date now = new Date();
        revokedTokens.entrySet().removeIf(entry -> entry.getValue().before(now));
    }
}
