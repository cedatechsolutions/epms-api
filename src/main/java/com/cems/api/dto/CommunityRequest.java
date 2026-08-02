package com.cems.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Set;

/**
 * Create/update payload for a community (spec Module 2 §3.2). The same shape serves both
 * operations. {@code classification} is checked against the allowed set in the service;
 * population fields are optional (GAD data captured when known). Server-side validation is
 * authoritative (cross-cutting rule 9).
 */
public record CommunityRequest(
        @NotBlank String name,
        String barangayCode,
        @NotBlank String municipality,
        @NotBlank String province,
        @NotBlank String classification,
        @PositiveOrZero Integer estimatedPopulation,
        @PositiveOrZero Integer householdCount,
        @PositiveOrZero Integer populationMale,
        @PositiveOrZero Integer populationFemale,
        String contactPersonName,
        String contactPersonDesignation,
        String contactPersonPhone,
        String notes,
        Set<String> sectorIds) {
}
