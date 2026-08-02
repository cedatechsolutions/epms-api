package com.cems.api.exception;

/**
 * The resource existed but is no longer available — HTTP 410. Used for public surveys that are
 * closed or past their closing date (spec Module 3 §3: "Closed/expired surveys show a friendly
 * closed message (410)").
 */
public class GoneException extends RuntimeException {

    public GoneException(String message) {
        super(message);
    }
}
