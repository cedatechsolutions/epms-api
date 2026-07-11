package com.cems.api.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Single source of truth for the six EPMS domain roles (spec §2.1).
 *
 * <p>Each role has a persisted {@code code} (snake_case, stored in {@code roles.name}
 * and exposed over the API) and a derived Spring Security {@code authority}
 * ({@code ROLE_<UPPER>}). Authorization checks use {@code hasRole('ADMIN')} style
 * expressions, which Spring maps to the {@code ROLE_}-prefixed authority.
 */
public enum RoleName {
    ADMIN("admin"),
    CAMPUS_ADMIN("campus_admin"),
    CAMPUS_EXTENSION_COORDINATOR("campus_extension_coordinator"),
    EXTENSION_COORDINATOR("extension_coordinator"),
    FACULTY("faculty"),
    STUDENT_VOLUNTEER("student_volunteer");

    private final String code;

    RoleName(String code) {
        this.code = code;
    }

    /** Persisted, API-facing role identifier, e.g. {@code "campus_admin"}. */
    public String code() {
        return code;
    }

    /** Spring Security authority for this role, e.g. {@code "ROLE_CAMPUS_ADMIN"}. */
    public String authority() {
        return "ROLE_" + name();
    }

    /** Maps a persisted role code to its authority; unknown codes are ignored by callers as needed. */
    public static String authorityForCode(String code) {
        return "ROLE_" + code.trim().toUpperCase(Locale.ROOT);
    }

    public static RoleName fromCode(String code) {
        return Arrays.stream(values())
                .filter(role -> role.code.equalsIgnoreCase(code == null ? null : code.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + code));
    }

    public static boolean isValidCode(String code) {
        return code != null && Arrays.stream(values()).anyMatch(role -> role.code.equalsIgnoreCase(code.trim()));
    }

    /** Comma-separated list of valid codes, for validation error messages. */
    public static String codesCsv() {
        return Arrays.stream(values()).map(RoleName::code).collect(Collectors.joining(", "));
    }

    /** Human-friendly label, e.g. {@code "Campus Extension Coordinator"}, for display/exports. */
    public String displayName() {
        return Arrays.stream(code.split("_"))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
    }

    /** Best-effort display label for a persisted code; falls back to the raw code if unknown. */
    public static String displayNameForCode(String code) {
        return isValidCode(code) ? fromCode(code).displayName() : (code == null ? "" : code);
    }
}
