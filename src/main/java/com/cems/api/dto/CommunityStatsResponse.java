package com.cems.api.dto;

/**
 * Summary-card counts for the community list (spec Module 2 §1).
 * {@code beneficiariesReached} (attendance, Phase 5) and {@code assessedThisSemester}
 * (surveys, Phase 2) are 0 until those modules land (plan Phase 1 scope note).
 */
public record CommunityStatsResponse(
        long totalCommunities,
        long beneficiariesReached,
        long assessedThisSemester) {
}
