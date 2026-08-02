package com.cems.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * The public form payload for {@code GET /api/public/surveys/{token}} (spec Module 3 §3).
 * Carries only what a respondent needs: the survey framing, the questions, and the sector options
 * for the optional GAD sector field. No respondent data, no internal ids beyond question ids.
 */
public record PublicSurveyResponse(
        String title,
        String description,
        String communityName,
        Instant closesAt,
        List<PublicQuestionResponse> questions,
        List<SectorResponse> sectors) {
}
