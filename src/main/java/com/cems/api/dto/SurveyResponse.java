package com.cems.api.dto;

import com.cems.api.entity.Survey;

import java.time.Instant;
import java.util.List;

/**
 * Full survey detail for the builder (spec Module 3 §1). {@code accessToken} is only populated
 * once the survey is deployed. {@code questions} are ordered by {@code orderIndex}.
 */
public record SurveyResponse(
        String id,
        String communityId,
        String communityName,
        String title,
        String description,
        String status,
        String accessToken,
        Instant opensAt,
        Instant closesAt,
        Integer targetResponses,
        int questionCount,
        List<QuestionResponse> questions,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static SurveyResponse fromEntity(Survey survey, List<QuestionResponse> questions) {
        return new SurveyResponse(
                survey.getId(),
                survey.getCommunity() == null ? null : survey.getCommunity().getId(),
                survey.getCommunity() == null ? null : survey.getCommunity().getName(),
                survey.getTitle(),
                survey.getDescription(),
                survey.getStatus(),
                survey.getAccessToken(),
                survey.getOpensAt(),
                survey.getClosesAt(),
                survey.getTargetResponses(),
                questions.size(),
                questions,
                survey.getCreatedBy(),
                survey.getCreatedAt(),
                survey.getUpdatedAt());
    }
}
