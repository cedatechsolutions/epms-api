package com.cems.api.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Service
public class RecaptchaService {

    private static final Logger logger = LoggerFactory.getLogger(RecaptchaService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    @Value("${app.recaptcha.enabled:false}")
    private boolean enabled;

    @Value("${app.recaptcha.site-secret:}")
    private String siteSecret;

    @Value("${app.recaptcha.verify-url:https://www.google.com/recaptcha/api/siteverify}")
    private String verifyUrl;

    public RecaptchaService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean verifyToken(String token, String remoteIp) {
        if (!enabled) {
            return true;
        }

        if (siteSecret == null || siteSecret.isBlank()) {
            throw new IllegalStateException("reCAPTCHA is enabled but the site secret is not configured.");
        }

        String formBody = buildFormBody(token, remoteIp);
        HttpRequest request = HttpRequest.newBuilder(URI.create(verifyUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.error(
                        "reCAPTCHA verification request failed with status {} and body {}",
                        response.statusCode(),
                        summarize(response.body()));
                throw new IllegalStateException("reCAPTCHA verification is unavailable (HTTP " + response.statusCode() + ").");
            }

            RecaptchaVerificationResponse verificationResponse = objectMapper.readValue(
                    response.body(),
                    RecaptchaVerificationResponse.class);

            if (!verificationResponse.success()) {
                logger.warn("reCAPTCHA verification failed with error codes {}", verificationResponse.errorCodes());
            }

            return verificationResponse.success();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.error("Unable to verify reCAPTCHA token", ex);
            String message = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? "reCAPTCHA verification is unavailable."
                    : "reCAPTCHA verification is unavailable: " + ex.getMessage();
            throw new IllegalStateException(message, ex);
        }
    }

    private String buildFormBody(String token, String remoteIp) {
        StringBuilder body = new StringBuilder();
        body.append("secret=").append(urlEncode(siteSecret));
        body.append("&response=").append(urlEncode(token));

        if (remoteIp != null && !remoteIp.isBlank()) {
            body.append("&remoteip=").append(urlEncode(remoteIp));
        }

        return body.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }

        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) + "..." : normalized;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RecaptchaVerificationResponse(
            boolean success,
            @JsonProperty("error-codes") List<String> errorCodes) {
    }
}
