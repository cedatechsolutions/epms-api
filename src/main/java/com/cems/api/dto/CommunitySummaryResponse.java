package com.cems.api.dto;

import com.cems.api.entity.Community;

import java.time.Instant;
import java.util.List;

/**
 * Community list-row projection (spec Module 2 §1). {@code lastAssessmentDate} and
 * {@code activeProgramCount} are derived from surveys/programs that arrive in later phases —
 * null/0 until then (plan Phase 1 scope note).
 */
public record CommunitySummaryResponse(
        String id,
        String name,
        String barangayCode,
        String municipality,
        String province,
        List<SectorResponse> sectors,
        Instant lastAssessmentDate,
        int activeProgramCount) {

    public static CommunitySummaryResponse fromEntity(Community community) {
        return new CommunitySummaryResponse(
                community.getId(),
                community.getName(),
                community.getBarangayCode(),
                community.getMunicipality(),
                community.getProvince(),
                community.getSectors().stream()
                        .map(SectorResponse::fromEntity)
                        .sorted(java.util.Comparator.comparing(SectorResponse::name))
                        .toList(),
                null, // lastAssessmentDate — Phase 2 (surveys)
                0);   // activeProgramCount — Phase 4 (programs)
    }
}
