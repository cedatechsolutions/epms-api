package com.cems.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * Create/update payload for a proposal (spec Module 5 §2). The same shape serves both operations.
 *
 * <p>Only {@code title} is required here, because <b>Save Draft must succeed with a title alone</b>
 * (spec Module 5 AC 2). The full field set is enforced separately at submit time by
 * {@code ProgramService.assertSubmittable} — putting those constraints on this record would make it
 * impossible to save a partial draft, which is the whole point of the draft state.
 */
public record ProgramRequest(
        @NotBlank String title,
        String communityId,
        String programTypeId,
        String surveyId,
        String objectives,
        @PositiveOrZero Integer targetBeneficiaries,
        LocalDate proposedDate,
        LocalDate endDate,
        String venue,
        @PositiveOrZero BigDecimal budgetRequested,
        String facultyLeadId,
        Set<String> sectorIds) {
}
