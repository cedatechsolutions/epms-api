package com.cems.api.service.impl;

import com.cems.api.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dev/local email implementation: logs the message instead of sending it. Replaced by an
 * SMTP-backed implementation in a later phase (spec Module 8). Do not log the temporary
 * password or reset link in production — this bean is intended for non-prod profiles.
 */
@Service
public class LoggingEmailService implements EmailService {

    private static final Logger logger = LoggerFactory.getLogger(LoggingEmailService.class);

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetLink) {
        logger.info("[email] Password reset for {} -> {}", toEmail, resetLink);
    }

    @Override
    public void sendTemporaryPasswordEmail(String toEmail, String temporaryPassword) {
        logger.info("[email] Temporary password for {} -> {}", toEmail, temporaryPassword);
    }
}
