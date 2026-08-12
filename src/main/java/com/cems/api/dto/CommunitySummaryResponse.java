package com.cems.api.dto;

import com.cems.api.entity.Community;

import java.time.Instant;
import java.util.List;

/**
 * Community list-row projection (spec Module 2 §1). {@code lastAssessmentDate} is derived from
 * surveys and is still null (Phase 2 leftover); {@code activeProgramCount} counts non-cancelled
 * programs and is supplied by the caller, which batches the counts for the whole page in one query.
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
        return fromEntity(community, 0);
    }

    public static CommunitySummaryResponse fromEntity(Community community, int activeProgramCount) {
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
                activeProgramCount);
    }
}
