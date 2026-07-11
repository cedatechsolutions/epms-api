package com.cems.api.service;

/**
 * Outbound transactional email. Phase 0 ships a logging implementation for local/dev
 * (per spec open questions: log emails to console until SMTP is configured); a real
 * async SMTP implementation replaces it later without changing callers.
 */
public interface EmailService {

    void sendPasswordResetEmail(String toEmail, String resetLink);

    void sendTemporaryPasswordEmail(String toEmail, String temporaryPassword);
}
