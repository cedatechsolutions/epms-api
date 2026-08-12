package com.cems.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One ranked recommendation (spec Module 4 §2). {@code breakdown} is the parsed
 * {@code score_breakdown} JSON — the UI's "why was this recommended?" popover renders it, and the
 * spec requires {@code matchScore} to be reproducible from it.
 *
 * <p>{@code spawnedProgramId} is the draft proposal created when this recommendation was accepted
 * or modified (Module 4 → Module 5 provenance); null for pending, rejected, or accepted-then-deleted
 * recommendations. It lets the card link straight to the proposal it produced.
 */
public record RecommendationResponse(
        String id,
        String surveyId,
        String programTypeId,
        String programTypeName,
        String programTypeDescription,
        String defaultDuration,
        BigDecimal matchScore,
        int rank,
        String status,
        String decidedBy,
        String decidedByName,
        Instant decidedAt,
        String decisionNote,
        Instant createdAt,
        ScoreBreakdown breakdown,
        String spawnedProgramId) {

    /**
     * The stored explanation. Kept as a record (not a raw JSON string) so the contract is typed on
     * both sides; it is serialized into {@code recommendations.score_breakdown} verbatim.
     */
    public record ScoreBreakdown(
            List<CategoryContribution> categories,
            BigDecimal rawScore,
            BigDecimal maxTheoretical,
            BigDecimal sectorBonus,
            boolean sectorBonusApplied,
            List<String> matchedSectors,
            BigDecimal matchScore) {
    }

    /** One need category's share of the raw score. */
    public record CategoryContribution(
            String needCategoryId,
            String needCategoryName,
            BigDecimal avgScore,
            String priority,
            BigDecimal multiplier,
            BigDecimal weight,
            BigDecimal contribution) {
    }
}
