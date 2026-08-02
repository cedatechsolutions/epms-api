package com.cems.api.dto;

import com.cems.api.entity.Survey;

import java.time.Instant;

/**
 * Survey list-row projection (spec Module 3 §1). {@code responseCount} is derived from
 * survey_responses (Pass B) — 0 until that table is wired.
 */
public record SurveySummaryResponse(
        String id,
        String title,
        String communityId,
        String communityName,
        String status,
        int questionCount,
        long responseCount,
        Instant opensAt,
        Instant closesAt,
        Instant createdAt) {

    public static SurveySummaryResponse fromEntity(Survey survey, int questionCount, long responseCount) {
        return new SurveySummaryResponse(
                survey.getId(),
                survey.getTitle(),
                survey.getCommunity() == null ? null : survey.getCommunity().getId(),
                survey.getCommunity() == null ? null : survey.getCommunity().getName(),
                survey.getStatus(),
                questionCount,
                responseCount,
                survey.getOpensAt(),
                survey.getClosesAt(),
                survey.getCreatedAt());
    }
}
