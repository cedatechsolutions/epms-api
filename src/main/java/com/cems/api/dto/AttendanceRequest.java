package com.cems.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * One attendee on one activity (spec Module 5b §4).
 *
 * <p>{@code sex} is {@code @NotBlank} here and validated against female/male in the service — it is
 * the column every GAD figure in the system is computed from, so it is never optional and never
 * coerced to a default.
 */
public record AttendanceRequest(
        @NotBlank String attendeeName,
        @NotBlank String sex,
        @PositiveOrZero @Max(130) Integer age,
        String sectorId) {
}
