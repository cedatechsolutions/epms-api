package com.cems.api.exception;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signals a field-level validation failure raised in the service layer (spec §5.1 → HTTP 422 with a
 * populated {@code fields} map).
 *
 * <p>Bean-validation annotations already cover per-request checks and produce the same envelope via
 * {@code MethodArgumentNotValidException}. This exception exists for rules that annotations cannot
 * express because they depend on <em>what the caller is doing</em>, not just on the payload — the
 * motivating case is submitting a proposal, where a draft may legally be saved with a title alone
 * but must be complete to enter the approval chain (spec Module 5 AC 2).
 *
 * <p>Prefer {@link IllegalArgumentException} for single-message 422s with no field to attach to.
 */
public class ValidationException extends RuntimeException {

    private final Map<String, List<String>> fields;

    public ValidationException(String message, Map<String, String> fieldMessages) {
        super(message);
        Map<String, List<String>> collected = new LinkedHashMap<>();
        if (fieldMessages != null) {
            fieldMessages.forEach((field, text) -> collected.put(field, List.of(text)));
        }
        this.fields = Map.copyOf(collected);
    }

    /** Field name → messages, in the shape the error envelope expects. */
    public Map<String, List<String>> getFields() {
        return fields;
    }
}
