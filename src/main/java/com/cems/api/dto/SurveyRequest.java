package com.cems.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * Create/update payload for a survey's metadata (spec Module 3 §1). Status transitions are handled
 * by the deploy/close endpoints, not here. Questions are managed via the question endpoints.
 */
public record SurveyRequest(
        @NotBlank String communityId,
        @NotBlank String title,
        String description,
        Instant opensAt,
        Instant closesAt,
        @PositiveOrZero Integer targetResponses) {
}
