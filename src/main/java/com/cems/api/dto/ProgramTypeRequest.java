package com.cems.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Create/update payload for a program type (spec Module 4 §4). Matrix weights are managed through
 * the scoring-matrix endpoint, not here — one pattern per problem.
 *
 * <p>{@code active} is nullable so a PATCH can leave it untouched; null means "keep as is".
 */
public record ProgramTypeRequest(
        @NotBlank String name,
        String description,
        String defaultDuration,
        Boolean active,
        List<String> sectorIds) {
}
