package com.cems.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Throttles authentication endpoints to a fixed number of requests per minute per client IP
 * (spec §5: 5/min on {@code /api/auth/*}), returning HTTP 429 in the standard error envelope.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private final IpRateLimiter rateLimiter;

    public AuthRateLimitFilter(@Value("${app.auth.rate-limit-per-minute:5}") int capacityPerMinute) {
        this.rateLimiter = new IpRateLimiter(capacityPerMinute);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (rateLimiter.tryConsume(IpRateLimiter.clientKey(request))) {
            filterChain.doFilter(request, response);
            return;
        }

        IpRateLimiter.writeTooManyRequests(response,
                "Too many requests. Please wait a minute and try again.");
    }
}
