package com.cems.api.dto;

import com.cems.api.entity.Community;

import java.time.Instant;
import java.util.List;

/**
 * Full community detail (spec Module 2 §2). {@code warnings} carries non-blocking data-integrity
 * notices (e.g. GAD population split differs from the estimate by &gt;10%). {@code documents} is
 * populated for the detail view; list rows use {@link CommunitySummaryResponse} instead.
 */
public record CommunityResponse(
        String id,
        String name,
        String barangayCode,
        String municipality,
        String province,
        String classification,
        Integer estimatedPopulation,
        Integer householdCount,
        Integer populationMale,
        Integer populationFemale,
        String contactPersonName,
        String contactPersonDesignation,
        String contactPersonPhone,
        String notes,
        List<SectorResponse> sectors,
        List<CommunityDocumentResponse> documents,
        List<String> warnings,
        Instant createdAt,
        Instant updatedAt) {

    public static CommunityResponse fromEntity(Community community,
            List<CommunityDocumentResponse> documents,
            List<String> warnings) {
        return new CommunityResponse(
                community.getId(),
                community.getName(),
                community.getBarangayCode(),
                community.getMunicipality(),
                community.getProvince(),
                community.getClassification(),
                community.getEstimatedPopulation(),
                community.getHouseholdCount(),
                community.getPopulationMale(),
                community.getPopulationFemale(),
                community.getContactPersonName(),
                community.getContactPersonDesignation(),
                community.getContactPersonPhone(),
                community.getNotes(),
                community.getSectors().stream()
                        .map(SectorResponse::fromEntity)
                        .sorted(java.util.Comparator.comparing(SectorResponse::name))
                        .toList(),
                documents,
                warnings,
                community.getCreatedAt(),
                community.getUpdatedAt());
    }
}
