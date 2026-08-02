package com.cems.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The results screen payload (spec Module 3 §4). Every person count is sex-disaggregated
 * (cross-cutting rule 1). When {@code finalized} is true the category scores are read from the
 * {@code assessment_results} snapshot rather than recomputed, so the displayed numbers never drift.
 */
public record SurveyResultsResponse(
        String surveyId,
        String title,
        String communityName,
        String status,
        boolean finalized,
        Instant finalizedAt,
        long totalResponses,
        long femaleResponses,
        long maleResponses,
        long undisclosedSexResponses,
        Integer targetResponses,
        /** Percent of the target reached, or null when no target was set. */
        BigDecimal completionRate,
        /** Highest category average, or null when nothing has been scored yet. */
        BigDecimal highestNeedScore,
        List<CategoryResult> categories,
        List<QuestionDistribution> distributions) {

    /** One ranked need category. */
    public record CategoryResult(
            String needCategoryId,
            String needCategoryName,
            BigDecimal avgScore,
            int responseCount,
            int femaleCount,
            int maleCount,
            String priority,
            int rank) {
    }

    /**
     * Answer distribution for a non-scored question type. Choice/checkbox questions report
     * {@code optionCounts}; open-text questions report the collected {@code textAnswers}.
     */
    public record QuestionDistribution(
            String questionId,
            String questionText,
            String questionType,
            Map<String, Long> optionCounts,
            List<String> textAnswers) {
    }
}
