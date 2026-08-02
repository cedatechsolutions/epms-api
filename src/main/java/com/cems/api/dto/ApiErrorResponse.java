package com.cems.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * Standard error envelope (spec §5.1): {@code {"error": {"code", "message", "fields"}}}.
 * {@code fields} is a per-field list of messages for validation failures and is omitted
 * when absent. The HTTP status line carries the numeric status (401/403/404/409/422/423).
 */
public class ApiErrorResponse {

    private final ErrorBody error;

    public ApiErrorResponse(String code, String message, Map<String, List<String>> fields) {
        this.error = new ErrorBody(code, message, fields);
    }

    public static ApiErrorResponse of(HttpStatus status, String message, Map<String, List<String>> fields) {
        return new ApiErrorResponse(codeFor(status), message, fields);
    }

    public ErrorBody getError() {
        return error;
    }

    /** Stable, machine-readable code derived from the HTTP status. */
    private static String codeFor(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case GONE -> "GONE";
            case UNPROCESSABLE_ENTITY -> "VALIDATION_FAILED";
            case LOCKED -> "LOCKED";
            case TOO_MANY_REQUESTS -> "RATE_LIMITED";
            case SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "BAD_REQUEST";
        };
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, Map<String, List<String>> fields) {
    }
}
