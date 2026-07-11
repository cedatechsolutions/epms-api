package com.cems.api.exception;

/** Signals a state conflict (spec §5.1 → HTTP 409), e.g. a uniqueness violation. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
