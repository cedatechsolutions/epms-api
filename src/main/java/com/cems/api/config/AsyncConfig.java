package com.cems.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables {@code @Async} execution so audit-log writes (and later, email dispatch) run off
 * the request thread. Uses Spring Boot's auto-configured {@code applicationTaskExecutor}.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
