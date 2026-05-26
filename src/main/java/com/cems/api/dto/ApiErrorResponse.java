package com.cems.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

public class ApiErrorResponse {

    private final Instant timestamp;
    private final int status;
    private final String error;
    private final String message;
    private final String path;
    private final Map<String, List<String>> errors;

    public ApiErrorResponse(HttpStatus status, String message, String path) {
        this(status, message, path, null);
    }

    public ApiErrorResponse(HttpStatus status,
            String message,
            String path,
            Map<String, List<String>> errors) {
        this.timestamp = Instant.now();
        this.status = status.value();
        this.error = status.getReasonPhrase();
        this.message = message;
        this.path = path;
        this.errors = errors;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getPath() {
        return path;
    }

    public Map<String, List<String>> getErrors() {
        return errors;
    }
}
