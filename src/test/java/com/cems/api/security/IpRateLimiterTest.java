package com.cems.api.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared per-IP token bucket behind both throttling filters. Functional tests run with the
 * limits effectively disabled, so the limiting behaviour itself is asserted here.
 */
class IpRateLimiterTest {

    @Test
    void allowsExactlyTheConfiguredNumberOfRequestsPerMinute() {
        // Spec §5: public survey submissions are limited to 10/min/IP.
        IpRateLimiter limiter = new IpRateLimiter(10);

        for (int attempt = 1; attempt <= 10; attempt++) {
            assertTrue(limiter.tryConsume("10.0.0.1"), "request " + attempt + " should be allowed");
        }
        assertFalse(limiter.tryConsume("10.0.0.1"), "the 11th request within the minute is throttled");
    }

    @Test
    void budgetsAreTrackedPerIpIndependently() {
        IpRateLimiter limiter = new IpRateLimiter(2);

        assertTrue(limiter.tryConsume("10.0.0.1"));
        assertTrue(limiter.tryConsume("10.0.0.1"));
        assertFalse(limiter.tryConsume("10.0.0.1"), "first IP is exhausted");

        // A different respondent must not be punished for the first one's traffic.
        assertTrue(limiter.tryConsume("10.0.0.2"));
        assertTrue(limiter.tryConsume("10.0.0.2"));
        assertFalse(limiter.tryConsume("10.0.0.2"));
    }
}
