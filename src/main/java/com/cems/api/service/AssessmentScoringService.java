package com.cems.api.service;

import com.cems.api.entity.AssessmentResult;
import com.cems.api.entity.NeedCategory;
import com.cems.api.entity.SurveyAnswer;
import com.cems.api.entity.SurveyQuestion;
import com.cems.api.entity.SurveyResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes ranked community needs from survey answers (spec Module 3 §4).
 *
 * <p><b>The formula, exactly as specified:</b> for each need category,
 * {@code weighted average = Σ(answer_value × question.weight) / Σ(question.weight)} taken over the
 * <b>rating</b> answers in that category. Choice/checkbox/open-text answers are collected and shown
 * as distributions but do <b>not</b> enter the numeric score in v1.
 *
 * <p>Counts are GAD-disaggregated: a respondent is counted once per category they answered, and
 * separately tallied as female/male (respondents who chose "prefer not to say" are included in
 * {@code responseCount} but in neither sex tally, so F + M ≤ total by design).
 *
 * <p>This class is pure and deterministic — the same answers always produce the same ranking. It is
 * covered by the spec-mandated fixture test (§5.4).
 */
@Service
public class AssessmentScoringService {

    private static final String TYPE_RATING = "rating";

    private final BigDecimal criticalThreshold;
    private final BigDecimal highThreshold;
    private final BigDecimal moderateThreshold;

    public AssessmentScoringService(
            @Value("${app.assessment.priority.critical:4.5}") BigDecimal criticalThreshold,
            @Value("${app.assessment.priority.high:3.5}") BigDecimal highThreshold,
            @Value("${app.assessment.priority.moderate:2.5}") BigDecimal moderateThreshold) {
        this.criticalThreshold = criticalThreshold;
        this.highThreshold = highThreshold;
        this.moderateThreshold = moderateThreshold;
    }

    /** One category's computed score. {@code rank} is 1-based, highest average first. */
    public record CategoryScore(
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
     * Scores every need category that received at least one rating answer.
     * Uncategorized questions contribute to no category (they have no need to rank).
     */
    public List<CategoryScore> score(List<SurveyAnswer> answers) {
        Map<String, Accumulator> byCategory = new LinkedHashMap<>();

        for (SurveyAnswer answer : answers) {
            SurveyQuestion question = answer.getSurveyQuestion();
            if (question == null || !TYPE_RATING.equals(question.getQuestionType())) {
                continue; // only rating answers are scored (v1)
            }
            NeedCategory category = question.getNeedCategory();
            if (category == null) {
                continue; // uncategorized questions belong to no need
            }
            Integer value = parseRating(answer.getAnswerValue());
            if (value == null) {
                continue;
            }

            Accumulator accumulator = byCategory.computeIfAbsent(
                    category.getId(), ignored -> new Accumulator(category));
            accumulator.add(value, question.getWeight(), answer.getSurveyResponse());
        }

        List<CategoryScore> scores = new ArrayList<>();
        for (Accumulator accumulator : byCategory.values()) {
            BigDecimal average = accumulator.average();
            if (average == null) {
                continue;
            }
            scores.add(new CategoryScore(
                    accumulator.category.getId(),
                    accumulator.category.getName(),
                    average,
                    accumulator.respondents.size(),
                    accumulator.femaleRespondents.size(),
                    accumulator.maleRespondents.size(),
                    priorityFor(average),
                    0)); // rank assigned below, once all averages are known
        }

        // Rank highest-scoring need first; ties break by name so the order is stable/reproducible.
        scores.sort(Comparator.comparing(CategoryScore::avgScore).reversed()
                .thenComparing(CategoryScore::needCategoryName));

        List<CategoryScore> ranked = new ArrayList<>(scores.size());
        for (int index = 0; index < scores.size(); index++) {
            CategoryScore score = scores.get(index);
            ranked.add(new CategoryScore(
                    score.needCategoryId(),
                    score.needCategoryName(),
                    score.avgScore(),
                    score.responseCount(),
                    score.femaleCount(),
                    score.maleCount(),
                    score.priority(),
                    index + 1));
        }
        return ranked;
    }

    /** Priority band for an average score (config-driven; defaults per spec §3.3). */
    public String priorityFor(BigDecimal average) {
        if (average.compareTo(criticalThreshold) >= 0) {
            return AssessmentResult.PRIORITY_CRITICAL;
        }
        if (average.compareTo(highThreshold) >= 0) {
            return AssessmentResult.PRIORITY_HIGH;
        }
        if (average.compareTo(moderateThreshold) >= 0) {
            return AssessmentResult.PRIORITY_MODERATE;
        }
        return AssessmentResult.PRIORITY_LOW;
    }

    private Integer parseRating(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(rawValue.trim());
        } catch (NumberFormatException ex) {
            return null; // defensive: submission validation already enforces 1–5
        }
    }

    /** Running totals for one need category. */
    private static final class Accumulator {

        private final NeedCategory category;
        private BigDecimal weightedSum = BigDecimal.ZERO;
        private BigDecimal weightSum = BigDecimal.ZERO;
        private final Set<String> respondents = new HashSet<>();
        private final Set<String> femaleRespondents = new HashSet<>();
        private final Set<String> maleRespondents = new HashSet<>();

        private Accumulator(NeedCategory category) {
            this.category = category;
        }

        private void add(int value, BigDecimal weight, SurveyResponse response) {
            BigDecimal safeWeight = weight == null ? BigDecimal.ONE : weight;
            weightedSum = weightedSum.add(BigDecimal.valueOf(value).multiply(safeWeight));
            weightSum = weightSum.add(safeWeight);

            if (response == null || response.getId() == null) {
                return;
            }
            respondents.add(response.getId());
            if (SurveyResponse.SEX_FEMALE.equals(response.getRespondentSex())) {
                femaleRespondents.add(response.getId());
            } else if (SurveyResponse.SEX_MALE.equals(response.getRespondentSex())) {
                maleRespondents.add(response.getId());
            }
            // "prefer_not_to_say" counts toward responseCount but neither sex tally.
        }

        private BigDecimal average() {
            if (weightSum.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }
            return weightedSum.divide(weightSum, 2, RoundingMode.HALF_UP);
        }
    }
}
