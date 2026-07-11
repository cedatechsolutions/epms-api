package com.cems.api.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Throttles authentication endpoints to a fixed number of requests per minute per client IP
 * (spec §5: 5/min on {@code /api/auth/*}), returning HTTP 429 in the standard error envelope.
 *
 * <p>In-memory, per-instance limiting via Bucket4j — sufficient for a single-node deployment;
 * swap the bucket store for a distributed backend (e.g. Redis) if the app is scaled out.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacityPerMinute;

    public AuthRateLimitFilter(@Value("${app.auth.rate-limit-per-minute:5}") int capacityPerMinute) {
        this.capacityPerMinute = capacityPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Bucket bucket = buckets.computeIfAbsent(clientKey(request), key -> newBucket());

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
            return;
        }

        writeTooManyRequests(response);
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacityPerMinute)
                .refillGreedy(capacityPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"error\":{\"code\":\"RATE_LIMITED\","
                        + "\"message\":\"Too many requests. Please wait a minute and try again.\"}}");
    }
}
