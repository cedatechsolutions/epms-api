package com.cems.api.dto;

/**
 * Counts behind the program list's status tabs (spec Module 5 §1: All / Draft / Under Review /
 * Approved / Ongoing / Completed).
 *
 * <p>{@code underReview} deliberately collapses the three in-chain statuses — {@code submitted},
 * {@code coordinator_review} and {@code recommending_approval} — into the single tab the spec asks
 * for. The per-status detail is still available on each row's badge.
 *
 * <p>Counts respect the caller's visibility: a faculty member sees counts for their own and led
 * proposals only.
 */
public record ProgramStatsResponse(
        long total,
        long draft,
        long underReview,
        long returned,
        long approved,
        long ongoing,
        long completed,
        long cancelled) {
}
