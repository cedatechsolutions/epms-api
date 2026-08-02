package com.cems.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Throttles public survey submissions to 10/min/IP (spec §5), returning HTTP 429 in the standard
 * error envelope. Only {@code POST /api/public/surveys/{token}/responses} is limited — reading the
 * form is not, so a legitimate respondent can always load the page.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class PublicSurveyRateLimitFilter extends OncePerRequestFilter {

    private final IpRateLimiter rateLimiter;

    public PublicSurveyRateLimitFilter(
            @Value("${app.public.survey-rate-limit-per-minute:10}") int capacityPerMinute) {
        this.rateLimiter = new IpRateLimiter(capacityPerMinute);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod())
                && request.getRequestURI().startsWith("/api/public/surveys/")
                && request.getRequestURI().endsWith("/responses"));
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
                "Too many submissions. Please wait a minute and try again.");
    }
}
