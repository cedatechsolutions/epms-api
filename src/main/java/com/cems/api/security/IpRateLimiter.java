package com.cems.api.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Fixed-rate, per-key token bucket (Bucket4j) shared by the request-throttling filters.
 *
 * <p>In-memory and per-instance — sufficient for a single-node deployment; swap the bucket store
 * for a distributed backend (e.g. Redis) if the app is scaled out.
 */
public class IpRateLimiter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacityPerMinute;

    public IpRateLimiter(int capacityPerMinute) {
        this.capacityPerMinute = capacityPerMinute;
    }

    /** Consumes one token for {@code key}; false when the caller has exhausted its minute budget. */
    public boolean tryConsume(String key) {
        return buckets.computeIfAbsent(key, ignored -> newBucket()).tryConsume(1);
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacityPerMinute)
                .refillGreedy(capacityPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /** The caller's IP, preferring the first {@code X-Forwarded-For} hop when behind a proxy. */
    public static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Writes HTTP 429 in the standard error envelope (spec §5.1). */
    public static void writeTooManyRequests(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"" + message + "\"}}");
    }
}
